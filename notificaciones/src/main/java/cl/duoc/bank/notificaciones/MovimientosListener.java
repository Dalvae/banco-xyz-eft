package cl.duoc.bank.notificaciones;

import cl.duoc.bank.shared.Eventos;
import cl.duoc.bank.shared.Eventos.RetiroRealizado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

// consumidor kafka: escucha los retiros que publica bank-api y guarda una notificacion
@Component
public class MovimientosListener {
    // con este id se busca el listener en el KafkaListenerEndpointRegistry para pausarlo
    public static final String ID = "movimientosListener";
    private static final Logger LOG = LoggerFactory.getLogger(MovimientosListener.class);
    private final JdbcTemplate jdbc;

    public MovimientosListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @KafkaListener(id = ID, topics = Eventos.TOPICO_MOVIMIENTOS, groupId = KafkaConsumerConfig.GRUPO)
    public void onMovimiento(RetiroRealizado evento, Acknowledgment ack) {
        String texto = "Se realizo un retiro de $%s. Saldo disponible: $%s".formatted(
                evento.monto().toPlainString(), evento.saldo().toPlainString());
        try {
            jdbc.update("INSERT INTO notificacion(operation_id, cuenta_id, titular, mensaje) VALUES (?,?,?,?)",
                    evento.operationId(), evento.cuentaId(), evento.titular(), texto);
            LOG.info("Evento consumido cuenta={} titular={} -> {}", evento.cuentaId(), evento.titular(), texto);
        } catch (DuplicateKeyException repetido) {
            LOG.info("El evento {} ya estaba guardado, lo salto", evento.operationId());
        }
        // si el insert falla no llega aca, no se confirma y kafka lo vuelve a entregar
        ack.acknowledge();
    }
}
