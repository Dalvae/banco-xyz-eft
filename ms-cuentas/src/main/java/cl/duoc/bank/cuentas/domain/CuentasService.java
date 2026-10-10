package cl.duoc.bank.cuentas.domain;

import static cl.duoc.bank.shared.DomainDtos.*;

import cl.duoc.bank.shared.ApiException;
import cl.duoc.bank.shared.Montos;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CuentasService {
    private static final Logger LOG = LoggerFactory.getLogger(CuentasService.class);
    private static final String COLUMNAS = "id,owner,display_name,masked_number,currency,balance,status,origin";
    private static final Set<String> MONEDAS = Set.of("CLP", "USD");
    private final JdbcTemplate jdbc;

    public CuentasService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Account> accounts(String subject) {
        return jdbc.query("SELECT " + COLUMNAS + " FROM account WHERE owner=? ORDER BY id", this::account, subject);
    }

    public Account balance(long accountId, String subject) {
        Account account = accountById(accountId, false);
        requireOwner(account, subject);
        return account;
    }

    public MovementPage movements(long accountId, int page, int size, String subject, boolean mobileOnly) {
        if (page < 0 || size < 1 || size > 100 || (mobileOnly && size > 5)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Paginacion fuera de rango");
        }
        requireOwner(accountById(accountId, false), subject);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM movement WHERE account_id=?", Long.class, accountId);
        List<Movement> items = jdbc.query("""
                SELECT id,account_id,movement_date,movement_type,amount,description,source
                FROM movement WHERE account_id=? ORDER BY movement_date DESC,id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> new Movement(rs.getLong(1), rs.getLong(2), rs.getDate(3).toLocalDate().toString(),
                        rs.getString(4), rs.getBigDecimal(5), rs.getString(6), rs.getString(7)), accountId, size, (long) page * size);
        return new MovementPage(items, page, size, total);
    }

    // ---- apertura, mantenimiento y cierre

    @Transactional
    public Account abrir(String subject, OpenAccount request) {
        String nombre = request == null || request.displayName() == null ? "" : request.displayName().trim();
        String moneda = request == null || request.currency() == null ? "CLP" : request.currency().trim().toUpperCase();
        if (nombre.isEmpty() || nombre.length() > 120 || !MONEDAS.contains(moneda)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Nombre obligatorio (max 120) y moneda CLP o USD");
        }
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO account(owner,display_name,masked_number,currency,balance,status,origin) "
                    + "VALUES(?,?,'****',?,0,'ABIERTA','apertura-web')", new String[] {"id"});
            ps.setString(1, subject);
            ps.setString(2, nombre);
            ps.setString(3, moneda);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        // el numero enmascarado lo armo con el id que me dio la bd
        jdbc.update("UPDATE account SET masked_number=? WHERE id=?", "****" + String.format("%04d", id % 10000), id);
        LOG.info("Cuenta abierta id={} titular={} moneda={}", id, subject, moneda);
        return accountById(id, false);
    }

    @Transactional
    public Account renombrar(long accountId, String subject, String displayName) {
        String nombre = displayName == null ? "" : displayName.trim();
        if (nombre.isEmpty() || nombre.length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Nombre obligatorio (max 120)");
        }
        Account cuenta = accountById(accountId, true);
        requireOwner(cuenta, subject);
        jdbc.update("UPDATE account SET display_name=? WHERE id=?", nombre, accountId);
        return accountById(accountId, false);
    }

    @Transactional
    public Account cerrar(long accountId, String subject) {
        Account cuenta = accountById(accountId, true);
        requireOwner(cuenta, subject);
        if (!"ABIERTA".equals(cuenta.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "La cuenta ya esta cerrada");
        }
        // no cierro con plata adentro, primero hay que transferirla
        if (cuenta.balance().signum() != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "La cuenta tiene saldo, transfierelo antes de cerrarla");
        }
        jdbc.update("UPDATE account SET status='CERRADA', closed_at=CURRENT_TIMESTAMP WHERE id=?", accountId);
        LOG.info("Cuenta cerrada id={} titular={}", accountId, subject);
        return accountById(accountId, false);
    }

    // ---- lo que usa ms-pagos. todo es idempotente por operationId

    @Transactional
    public LegResult debitar(long accountId, LegRequest request) {
        BigDecimal monto = validarPierna(request);
        Account cuenta = accountById(accountId, true);
        LegResult previo = previa(request.operationId(), accountId, monto, "DEBITO");
        if (previo != null) return previo;
        if (request.owner() != null) requireOwner(cuenta, request.owner());
        requireAbierta(cuenta);
        if (cuenta.balance().compareTo(monto) < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Fondos insuficientes");
        }
        BigDecimal despues = cuenta.balance().subtract(monto);
        aplicar(cuenta, request, "DEBITO", monto, despues);
        return new LegResult(request.operationId(), accountId, cuenta.owner(), monto, despues, cuenta.currency(), "APLICADA");
    }

    @Transactional
    public LegResult acreditar(long accountId, LegRequest request) {
        BigDecimal monto = validarPierna(request);
        Account cuenta = accountById(accountId, true);
        LegResult previo = previa(request.operationId(), accountId, monto, "CREDITO");
        if (previo != null) return previo;
        requireAbierta(cuenta);
        BigDecimal despues = cuenta.balance().add(monto);
        aplicar(cuenta, request, "CREDITO", monto, despues);
        return new LegResult(request.operationId(), accountId, cuenta.owner(), monto, despues, cuenta.currency(), "APLICADA");
    }

    // la compensacion de la saga. si la operacion se aplico la devuelvo, si nunca llego dejo una marca ANULADA
    // para que si el debito llega tarde (timeout de red) se rechace en vez de aplicarse
    @Transactional
    public LegResult revertir(String operationId) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "operationId invalido");
        }
        var filas = jdbc.query("SELECT account_id,tipo,amount,revertida FROM operacion_aplicada WHERE operation_id=? FOR UPDATE",
                (rs, n) -> new OpAplicada(rs.getObject(1, Long.class), rs.getString(2), rs.getBigDecimal(3), rs.getBoolean(4)), operationId);
        if (filas.isEmpty()) {
            try {
                jdbc.update("INSERT INTO operacion_aplicada(operation_id,account_id,tipo,amount,balance_after,revertida) VALUES(?,NULL,'ANULADA',0,0,TRUE)", operationId);
            } catch (DuplicateKeyException justoLlego) {
                // se aplico mientras tanto, ms-pagos va a reintentar la reversa y ahi si la encuentra
                throw new ApiException(HttpStatus.CONFLICT, "La operacion se esta aplicando, reintenta la reversa");
            }
            LOG.info("Reversa de {}: no se habia aplicado, queda anulada", operationId);
            return new LegResult(operationId, 0, null, BigDecimal.ZERO, BigDecimal.ZERO, null, "ANULADA");
        }
        OpAplicada op = filas.get(0);
        String tipo = op.tipo();
        BigDecimal monto = op.monto();
        long accountId = op.accountId() == null ? 0 : op.accountId();
        if (op.revertida()) {
            return new LegResult(operationId, accountId, null, monto, BigDecimal.ZERO, null, "ANULADA".equals(tipo) ? "ANULADA" : "REVERTIDA");
        }
        Account cuenta = accountById(accountId, true);
        BigDecimal despues = "DEBITO".equals(tipo) ? cuenta.balance().add(monto) : cuenta.balance().subtract(monto);
        if (despues.signum() < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "No hay saldo para revertir el credito");
        }
        jdbc.update("UPDATE account SET balance=? WHERE id=?", despues, accountId);
        jdbc.update("UPDATE operacion_aplicada SET revertida=TRUE WHERE operation_id=?", operationId);
        jdbc.update("INSERT INTO movement(account_id,movement_date,movement_type,amount,description,source,operation_id) VALUES(?,CURRENT_DATE,'reversa',?,?,'operacion',?)",
                accountId, monto, "Reversa de " + operationId, operationId + "-rev");
        LOG.info("Reversa de {} ({}) cuenta={} monto={} saldo={}", operationId, tipo, accountId, monto, despues);
        return new LegResult(operationId, accountId, cuenta.owner(), monto, despues, cuenta.currency(), "REVERTIDA");
    }

    private void aplicar(Account cuenta, LegRequest request, String tipo, BigDecimal monto, BigDecimal despues) {
        try {
            jdbc.update("INSERT INTO operacion_aplicada(operation_id,account_id,tipo,amount,balance_after) VALUES(?,?,?,?,?)",
                    request.operationId(), cuenta.id(), tipo, monto, despues);
        } catch (DuplicateKeyException repetida) {
            // otra cuenta ya uso este id, o justo la anularon. tiro error y se hace rollback
            throw new ApiException(HttpStatus.CONFLICT, "operationId ya utilizado");
        }
        jdbc.update("UPDATE account SET balance=? WHERE id=?", despues, cuenta.id());
        String descripcion = request.description() == null || request.description().isBlank() ? tipo.toLowerCase() : request.description();
        jdbc.update("INSERT INTO movement(account_id,movement_date,movement_type,amount,description,source,operation_id) VALUES(?,CURRENT_DATE,?,?,?,'operacion',?)",
                cuenta.id(), request.type() == null ? tipo.toLowerCase() : request.type(), monto, descripcion, request.operationId());
        LOG.info("{} cuenta={} op={} monto={} saldo={}", tipo, cuenta.id(), request.operationId(), monto, despues);
    }

    private LegResult previa(String operationId, long accountId, BigDecimal monto, String tipo) {
        return jdbc.query("SELECT account_id,tipo,amount,balance_after,revertida FROM operacion_aplicada WHERE operation_id=?", (rs, n) -> {
            if ("ANULADA".equals(rs.getString(2))) {
                throw new ApiException(HttpStatus.CONFLICT, "La operacion fue anulada");
            }
            if (rs.getLong(1) != accountId || !tipo.equals(rs.getString(2)) || rs.getBigDecimal(3).compareTo(monto) != 0) {
                throw new ApiException(HttpStatus.CONFLICT, "operationId reutilizado con otra operacion");
            }
            return new LegResult(operationId, accountId, null, rs.getBigDecimal(3), rs.getBigDecimal(4), null,
                    rs.getBoolean(5) ? "REVERTIDA" : "APLICADA");
        }, operationId).stream().findFirst().orElse(null);
    }

    private BigDecimal validarPierna(LegRequest request) {
        if (request == null || request.operationId() == null || request.operationId().isBlank() || request.operationId().length() > 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "operationId es obligatorio (max 60)");
        }
        return Montos.validar(request.amount());
    }

    private Account accountById(long id, boolean lock) {
        String sql = "SELECT " + COLUMNAS + " FROM account WHERE id=?" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, this::account, id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Cuenta no encontrada"));
    }

    private Account account(ResultSet rs, int row) throws SQLException {
        return new Account(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getBigDecimal(6), rs.getString(7), rs.getString(8));
    }

    private void requireOwner(Account account, String subject) {
        if (!account.owner().equals(subject)) throw new ApiException(HttpStatus.FORBIDDEN, "La cuenta no pertenece al titular autenticado");
    }

    private record OpAplicada(Long accountId, String tipo, BigDecimal monto, boolean revertida) { }

    private void requireAbierta(Account account) {
        if (!"ABIERTA".equals(account.status())) throw new ApiException(HttpStatus.CONFLICT, "La cuenta esta cerrada");
    }
}
