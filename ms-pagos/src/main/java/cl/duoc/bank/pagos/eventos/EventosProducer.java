package cl.duoc.bank.pagos.eventos;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

// productor kafka con outbox (viene de s8): cada 5s busca los eventos que no se han mandado y los publica.
// si kafka esta caido quedan en la tabla y se mandan cuando vuelva.
// si hay 2 replicas de ms-pagos puede salir un evento repetido, por eso notificaciones descarta por id
@Service
public class EventosProducer {
    private static final Logger LOG = LoggerFactory.getLogger(EventosProducer.class);
    private final KafkaTemplate<String, String> kafka;
    private final JdbcTemplate jdbc;

    public EventosProducer(KafkaTemplate<String, String> kafka, JdbcTemplate jdbc) {
        this.kafka = kafka;
        this.jdbc = jdbc;
    }

    record Pendiente(String id, String topico, String clave, String payload) { }

    @Scheduled(initialDelayString = "${eventos.reintento-ms:5000}", fixedDelayString = "${eventos.reintento-ms:5000}")
    public void enviarPendientes() {
        List<Pendiente> pendientes = jdbc.query("""
                SELECT id,topico,clave,payload FROM evento_pendiente
                WHERE enviado=FALSE ORDER BY creado_en LIMIT 50
                """, (rs, n) -> new Pendiente(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        for (Pendiente evento : pendientes) {
            try {
                // la key es la cuenta (o el titular en las alertas), asi los de una misma cuenta van en orden
                RecordMetadata meta = kafka.send(evento.topico(), evento.clave(), evento.payload())
                        .get(5, TimeUnit.SECONDS).getRecordMetadata();
                jdbc.update("UPDATE evento_pendiente SET enviado=TRUE WHERE id=?", evento.id());
                LOG.info("Evento publicado en {} particion={} offset={} key={}", meta.topic(), meta.partition(), meta.offset(), evento.clave());
            } catch (Exception e) {
                jdbc.update("UPDATE evento_pendiente SET intentos=intentos+1 WHERE id=?", evento.id());
                LOG.warn("kafka no responde, el evento {} queda pendiente y se reintenta: {}", evento.id(), e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // si kafka esta caido no tiene sentido seguir con los demas
                return;
            }
        }
    }
}
