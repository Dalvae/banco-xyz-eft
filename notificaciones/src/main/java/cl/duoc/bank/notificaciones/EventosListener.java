package cl.duoc.bank.notificaciones;

import cl.duoc.bank.shared.Eventos;
import cl.duoc.bank.shared.Eventos.AlertaSeguridad;
import cl.duoc.bank.shared.Eventos.TransaccionCompletada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

// consumidor kafka: escucha las transacciones y las alertas de seguridad que publica ms-pagos
@Component
public class EventosListener {
    // con estos ids se buscan los listeners en el KafkaListenerEndpointRegistry para pausarlos
    public static final String TRANSACCIONES = "transaccionesListener";
    public static final String ALERTAS = "alertasListener";
    private static final Logger LOG = LoggerFactory.getLogger(EventosListener.class);
    private final JdbcTemplate jdbc;

    public EventosListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @KafkaListener(id = TRANSACCIONES, topics = Eventos.TOPICO_TRANSACCIONES, groupId = KafkaConsumerConfig.GRUPO)
    public void onTransaccion(TransaccionCompletada evento, Acknowledgment ack) {
        String texto = switch (evento.tipo()) {
            case "RETIRO" -> "Retiro de $%s en %s. Saldo disponible: $%s".formatted(evento.monto().toPlainString(), evento.canal(), plano(evento));
            case "DEPOSITO" -> "Deposito de $%s en la cuenta %d".formatted(evento.monto().toPlainString(), evento.cuentaDestino());
            case "TRANSFERENCIA" -> "Transferencia de $%s a la cuenta %d. Saldo disponible: $%s".formatted(
                    evento.monto().toPlainString(), evento.cuentaDestino(), plano(evento));
            default -> "Pago de $%s. Saldo disponible: $%s".formatted(evento.monto().toPlainString(), plano(evento));
        };
        Long cuenta = evento.cuentaOrigen() != null ? evento.cuentaOrigen() : evento.cuentaDestino();
        guardar(evento.operationId(), Eventos.TOPICO_TRANSACCIONES, evento.tipo(), cuenta, evento.titular(), texto);
        // si el insert falla no llega aca y no se confirma: el error handler lo reintenta con espera y si sigue fallando va al .DLT
        ack.acknowledge();
    }

    @KafkaListener(id = ALERTAS, topics = Eventos.TOPICO_ALERTAS, groupId = KafkaConsumerConfig.GRUPO)
    public void onAlerta(AlertaSeguridad alerta, Acknowledgment ack) {
        // aca en un banco de verdad iria el sms o el correo al cliente y un aviso al equipo de fraude
        LOG.warn("ALERTA DE SEGURIDAD {} titular={} cuenta={}: {}", alerta.tipo(), alerta.titular(), alerta.cuentaId(), alerta.detalle());
        guardar(alerta.alertaId(), Eventos.TOPICO_ALERTAS, alerta.tipo(), alerta.cuentaId(), alerta.titular(),
                "Alerta " + alerta.tipo() + ": " + alerta.detalle());
        ack.acknowledge();
    }

    private void guardar(String eventoId, String topico, String tipo, Long cuenta, String titular, String texto) {
        try {
            jdbc.update("INSERT INTO notificacion(evento_id, topico, tipo, cuenta_id, titular, mensaje) VALUES (?,?,?,?,?,?)",
                    eventoId, topico, tipo, cuenta, titular, texto);
            LOG.info("Evento consumido de {} titular={} -> {}", topico, titular, texto);
        } catch (DuplicateKeyException repetido) {
            LOG.info("El evento {} ya estaba guardado, lo salto", eventoId);
        }
    }

    private static String plano(TransaccionCompletada evento) {
        return evento.saldoOrigen() == null ? "-" : evento.saldoOrigen().toPlainString();
    }
}
