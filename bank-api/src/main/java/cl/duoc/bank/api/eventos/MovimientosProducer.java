package cl.duoc.bank.api.eventos;

import cl.duoc.bank.shared.Eventos;
import cl.duoc.bank.shared.Eventos.RetiroRealizado;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

// productor kafka con outbox: cada 5s busca los retiros que todavia no se mandaron y los publica.
// si kafka esta caido no pasa nada, quedan en la tabla y se mandan cuando vuelva
@Service
public class MovimientosProducer {
    private static final Logger LOG = LoggerFactory.getLogger(MovimientosProducer.class);
    private final KafkaTemplate<String, RetiroRealizado> kafka;
    private final JdbcTemplate jdbc;

    public MovimientosProducer(KafkaTemplate<String, RetiroRealizado> kafka, JdbcTemplate jdbc) {
        this.kafka = kafka;
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelayString = "${eventos.reintento-ms:5000}", fixedDelayString = "${eventos.reintento-ms:5000}")
    public void enviarPendientes() {
        List<RetiroRealizado> pendientes = jdbc.query("""
                SELECT operation_id,cuenta_id,titular,monto,saldo,fecha FROM evento_pendiente
                WHERE enviado=FALSE ORDER BY fecha LIMIT 50
                """, (rs, n) -> new RetiroRealizado(rs.getString(1), rs.getLong(2), rs.getString(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getTimestamp(6).toInstant()));
        for (RetiroRealizado evento : pendientes) {
            try {
                // la key es la cuenta, asi los retiros de una misma cuenta caen en la misma particion
                RecordMetadata meta = kafka.send(Eventos.TOPICO_MOVIMIENTOS, String.valueOf(evento.cuentaId()), evento)
                        .get(5, TimeUnit.SECONDS).getRecordMetadata();
                jdbc.update("UPDATE evento_pendiente SET enviado=TRUE WHERE operation_id=?", evento.operationId());
                LOG.info("Evento publicado en {} particion={} offset={} cuenta={} monto={}",
                        meta.topic(), meta.partition(), meta.offset(), evento.cuentaId(), evento.monto());
            } catch (Exception e) {
                jdbc.update("UPDATE evento_pendiente SET intentos=intentos+1 WHERE operation_id=?", evento.operationId());
                LOG.warn("kafka no responde, el evento {} queda pendiente y se reintenta: {}", evento.operationId(), e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // si kafka esta caido no tiene sentido seguir con los demas, se prueba en la proxima vuelta
                return;
            }
        }
    }
}
