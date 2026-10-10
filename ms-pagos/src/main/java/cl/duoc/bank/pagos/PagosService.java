package cl.duoc.bank.pagos;

import static cl.duoc.bank.shared.DomainDtos.LegRequest;
import static cl.duoc.bank.shared.DomainDtos.LegResult;
import static cl.duoc.bank.shared.DomainDtos.Receipt;

import cl.duoc.bank.shared.ApiException;
import cl.duoc.bank.shared.Eventos;
import cl.duoc.bank.shared.Eventos.AlertaSeguridad;
import cl.duoc.bank.shared.Eventos.TransaccionCompletada;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

// cada operacion es una saga chica:
//   1. queda guardada como PENDIENTE
//   2. se le pide a ms-cuentas el debito y/o el credito (cada pierna con su operationId, idempotente)
//   3. si todo sale bien -> COMPLETADA + eventos en el outbox, en la misma transaccion
//   si el credito de una transferencia falla o ms-cuentas se cae a la mitad, se compensa pidiendo la reversa.
// no use transacciones distribuidas (2PC) porque cada servicio tiene su bd y kafka no participa, con la saga
// el saldo queda consistente aunque sea un rato despues (consistencia eventual)
@Service
public class PagosService {
    private static final Logger LOG = LoggerFactory.getLogger(PagosService.class);
    private static final BigDecimal MAX_RETIRO = new BigDecimal("200000.00");
    private static final String COLUMNAS = "operation_id,tipo,cuenta_origen,cuenta_destino,monto,saldo_origen,estado,detalle";
    private final CuentasClient cuentas;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final BigDecimal montoAlto;
    private final int intentosFallidos;

    public PagosService(CuentasClient cuentas, JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json,
                        @Value("${pagos.alertas.monto-alto:150000}") BigDecimal montoAlto,
                        @Value("${pagos.alertas.intentos-fallidos:3}") int intentosFallidos) {
        this.cuentas = cuentas;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.montoAlto = montoAlto;
        this.intentosFallidos = intentosFallidos;
    }

    record Op(String id, String tipo, String titular, Long origen, Long destino, String convenio, BigDecimal monto, String canal) { }

    public Receipt retirar(String titular, Long cuenta, BigDecimal monto, String key, String canal) {
        if (cuenta == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Falta la cuenta");
        if (monto != null && monto.compareTo(MAX_RETIRO) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Monto invalido: maximo 200000 por retiro");
        }
        return ejecutar(new Op(null, "RETIRO", titular, cuenta, null, null, monto, canal), key);
    }

    public Receipt depositar(String titular, Long cuenta, BigDecimal monto, String key, String canal) {
        if (cuenta == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Falta la cuenta");
        return ejecutar(new Op(null, "DEPOSITO", titular, null, cuenta, null, monto, canal), key);
    }

    public Receipt transferir(String titular, Long origen, Long destino, BigDecimal monto, String key, String canal) {
        if (origen == null || destino == null || origen.equals(destino)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cuenta de origen y destino obligatorias y distintas");
        }
        return ejecutar(new Op(null, "TRANSFERENCIA", titular, origen, destino, null, monto, canal), key);
    }

    public Receipt pagarServicio(String titular, Long cuenta, String convenio, BigDecimal monto, String key, String canal) {
        if (cuenta == null || convenio == null || convenio.isBlank() || convenio.length() > 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cuenta y convenio obligatorios");
        }
        return ejecutar(new Op(null, "PAGO", titular, cuenta, null, convenio.trim(), monto, canal), key);
    }

    public List<Receipt> operaciones(String titular) {
        return jdbc.query("SELECT " + COLUMNAS + " FROM operacion WHERE titular=? ORDER BY creada_en DESC LIMIT 10",
                (rs, n) -> recibo(rs), titular);
    }

    private Receipt ejecutar(Op pedido, String key) {
        BigDecimal monto = validar(pedido.monto(), key);
        Receipt previo = previa(key, pedido, monto);
        if (previo != null) return previo;

        Op op = new Op(UUID.randomUUID().toString(), pedido.tipo(), pedido.titular(), pedido.origen(), pedido.destino(),
                pedido.convenio(), monto, pedido.canal());
        try {
            jdbc.update("INSERT INTO operacion(operation_id,idempotency_key,tipo,titular,cuenta_origen,cuenta_destino,convenio,monto,canal,estado) "
                    + "VALUES(?,?,?,?,?,?,?,?,?,'PENDIENTE')", op.id(), key, op.tipo(), op.titular(), op.origen(), op.destino(),
                    op.convenio(), monto, op.canal());
        } catch (DuplicateKeyException alMismoTiempo) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya hay una operacion con esa Idempotency-Key");
        }

        BigDecimal saldo = null;
        try {
            if (op.origen() != null) {
                saldo = cuentas.debitar(op.origen(), new LegRequest(op.id() + "-deb", monto, op.titular(),
                        op.tipo().toLowerCase(), descripcion(op, true))).balance();
            }
        } catch (ResponseStatusException rechazo) {
            // sin fondos, cuenta ajena o cerrada: no se movio nada
            rechazar(op, rechazo.getReason());
            throw rechazo;
        } catch (CuentasNoDisponible caido) {
            throw compensarYAvisar(op, "ms-cuentas no respondio al debitar");
        }

        if (op.destino() != null) {
            try {
                LegResult credito = cuentas.acreditar(op.destino(), new LegRequest(op.id() + "-cre", monto, null,
                        op.tipo().toLowerCase(), descripcion(op, false)));
                // en un deposito no hay origen, el saldo que se muestra es el de la cuenta que recibio
                if (op.origen() == null) saldo = credito.balance();
            } catch (ResponseStatusException rechazo) {
                if (op.origen() == null) {
                    rechazar(op, rechazo.getReason());
                    throw rechazo;
                }
                // el debito ya se hizo y el destino no recibe: hay que devolver la plata
                boolean devuelta = compensar(op);
                estado(op, devuelta ? "REVERTIDA" : "COMPENSACION_PENDIENTE", "Destino rechazo el abono: " + rechazo.getReason());
                throw new ApiException(HttpStatus.CONFLICT, "La cuenta destino no puede recibir (" + rechazo.getReason()
                        + "), el monto " + (devuelta ? "se devolvio" : "se devolvera") + " a la cuenta de origen");
            } catch (CuentasNoDisponible caido) {
                throw compensarYAvisar(op, "ms-cuentas no respondio al acreditar");
            }
        }
        return completar(op, saldo);
    }

    private Receipt completar(Op op, BigDecimal saldo) {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE operacion SET estado='COMPLETADA', saldo_origen=?, actualizada_en=CURRENT_TIMESTAMP WHERE operation_id=?", saldo, op.id());
            Instant ahora = Instant.now();
            Long cuentaClave = op.origen() != null ? op.origen() : op.destino();
            encolar(Eventos.TOPICO_TRANSACCIONES, String.valueOf(cuentaClave), new TransaccionCompletada(op.id(), op.tipo(),
                    op.origen(), op.destino(), op.titular(), op.monto(), saldo, op.canal(), ahora));
            // un retiro/transferencia/pago grande genera una alerta de seguridad
            if (op.origen() != null && op.monto().compareTo(montoAlto) >= 0) {
                encolar(Eventos.TOPICO_ALERTAS, op.titular(), new AlertaSeguridad(UUID.randomUUID().toString(), "MONTO_ALTO",
                        op.titular(), op.origen(), op.tipo() + " por $" + op.monto().toPlainString() + " desde " + op.canal(), ahora));
            }
        });
        LOG.info("Operacion {} {} COMPLETADA titular={} monto={}", op.tipo(), op.id(), op.titular(), op.monto());
        return new Receipt(op.id(), op.tipo(), op.origen(), op.destino(), op.monto(), saldo, "COMPLETADA", null);
    }

    private void rechazar(Op op, String motivo) {
        tx.executeWithoutResult(status -> {
            estado(op, "RECHAZADA", motivo);
            // varios rechazos seguidos del mismo titular (sin fondos, cuentas ajenas) puede ser alguien probando
            Integer rechazos = jdbc.queryForObject("SELECT COUNT(*) FROM operacion WHERE titular=? AND estado='RECHAZADA' AND creada_en > ?",
                    Integer.class, op.titular(), Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)));
            if (rechazos != null && rechazos >= intentosFallidos && rechazos % intentosFallidos == 0) {
                encolar(Eventos.TOPICO_ALERTAS, op.titular(), new AlertaSeguridad(UUID.randomUUID().toString(), "INTENTOS_FALLIDOS",
                        op.titular(), op.origen() != null ? op.origen() : op.destino(),
                        rechazos + " operaciones rechazadas en 10 minutos, la ultima: " + motivo, Instant.now()));
            }
        });
        LOG.info("Operacion {} {} RECHAZADA titular={}: {}", op.tipo(), op.id(), op.titular(), motivo);
    }

    private ApiException compensarYAvisar(Op op, String motivo) {
        boolean ok = compensar(op);
        estado(op, ok ? "REVERTIDA" : "COMPENSACION_PENDIENTE", motivo);
        LOG.warn("Operacion {} {}: {} -> {}", op.tipo(), op.id(), motivo, ok ? "revertida" : "queda para el reconciliador");
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No pudimos completar la operacion, no se hizo ningun cargo. Intenta en unos segundos");
    }

    // pide la reversa de las 2 piernas. si una nunca llego, ms-cuentas la deja anulada (asi no se aplica tarde)
    private boolean compensar(Op op) {
        try {
            if (op.origen() != null) cuentas.revertir(op.id() + "-deb");
            if (op.destino() != null) cuentas.revertir(op.id() + "-cre");
            return true;
        } catch (RuntimeException e) {
            LOG.warn("No se pudo compensar {} todavia: {}", op.id(), e.toString());
            return false;
        }
    }

    // si ms-cuentas estaba caido al compensar, o ms-pagos se murio a la mitad de una saga, aca se termina
    @Scheduled(initialDelayString = "${pagos.reconciliacion-ms:15000}", fixedDelayString = "${pagos.reconciliacion-ms:15000}")
    public void reconciliar() {
        List<Op> colgadas = jdbc.query("""
                SELECT operation_id,tipo,titular,cuenta_origen,cuenta_destino,convenio,monto,canal FROM operacion
                WHERE estado='COMPENSACION_PENDIENTE' OR (estado='PENDIENTE' AND creada_en < ?) ORDER BY creada_en LIMIT 20
                """, (rs, n) -> new Op(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, Long.class),
                rs.getObject(5, Long.class), rs.getString(6), rs.getBigDecimal(7), rs.getString(8)),
                Timestamp.from(Instant.now().minus(60, ChronoUnit.SECONDS)));
        for (Op op : colgadas) {
            if (compensar(op)) {
                estado(op, "REVERTIDA", "Revertida por el reconciliador");
                LOG.info("Reconciliador: operacion {} revertida", op.id());
            }
        }
    }

    private void estado(Op op, String estado, String detalle) {
        jdbc.update("UPDATE operacion SET estado=?, detalle=?, actualizada_en=CURRENT_TIMESTAMP WHERE operation_id=?",
                estado, detalle == null ? null : detalle.substring(0, Math.min(200, detalle.length())), op.id());
    }

    private void encolar(String topico, String clave, Object evento) {
        try {
            jdbc.update("INSERT INTO evento_pendiente(id,topico,clave,payload) VALUES(?,?,?,?)",
                    UUID.randomUUID().toString(), topico, clave, json.writeValueAsString(evento));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("no se pudo armar el evento", e);
        }
    }

    private Receipt previa(String key, Op pedido, BigDecimal monto) {
        return jdbc.query("SELECT " + COLUMNAS + ",titular FROM operacion WHERE idempotency_key=?", (rs, n) -> {
            Receipt r = recibo(rs);
            if (!r.type().equals(pedido.tipo()) || r.amount().compareTo(monto) != 0 || !rs.getString(9).equals(pedido.titular())
                    || !Objects.equals(r.fromAccount(), pedido.origen()) || !Objects.equals(r.toAccount(), pedido.destino())) {
                throw new ApiException(HttpStatus.CONFLICT, "Idempotency-Key reutilizada con otra operacion");
            }
            if (r.status().equals("PENDIENTE") || r.status().equals("COMPENSACION_PENDIENTE")) {
                throw new ApiException(HttpStatus.CONFLICT, "La operacion todavia se esta procesando");
            }
            return r;
        }, key).stream().findFirst().orElse(null);
    }

    private Receipt recibo(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Receipt(rs.getString(1), rs.getString(2), rs.getObject(3, Long.class), rs.getObject(4, Long.class),
                rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8));
    }

    private static BigDecimal validar(BigDecimal monto, String key) {
        if (key == null || key.isBlank() || key.length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key es obligatorio y debe tener hasta 120 caracteres");
        }
        if (monto == null || monto.signum() <= 0 || monto.scale() > 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Monto invalido: debe ser positivo y con hasta 2 decimales");
        }
        return monto.setScale(2);
    }

    private static String descripcion(Op op, boolean salida) {
        return switch (op.tipo()) {
            case "RETIRO" -> "Retiro por " + op.canal();
            case "DEPOSITO" -> "Deposito por " + op.canal();
            case "PAGO" -> "Pago de servicio " + op.convenio();
            default -> salida ? "Transferencia a cuenta " + op.destino() : "Transferencia desde cuenta " + op.origen();
        };
    }
}
