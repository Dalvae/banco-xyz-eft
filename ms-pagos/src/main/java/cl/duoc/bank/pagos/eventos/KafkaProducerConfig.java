package cl.duoc.bank.pagos.eventos;

import cl.duoc.bank.shared.Eventos;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

// config del productor armada como en la clase de la semana 8: ProducerFactory + KafkaTemplate + los topicos.
// el value ahora es String porque el outbox ya guarda el evento como json (sirve para los 2 topicos)
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // replicas y particiones salen de la config (en docker hay 1 broker asi que replicas=1, en aws con msk serian 3)
    @Value("${banco.kafka.replicas:1}")
    private int replicas;

    @Bean
    NewTopic topicoTransacciones() {
        return TopicBuilder.name(Eventos.TOPICO_TRANSACCIONES)
                .partitions(3)
                .replicas(replicas)
                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000") // 7 dias
                .build();
    }

    @Bean
    NewTopic topicoAlertas() {
        return TopicBuilder.name(Eventos.TOPICO_ALERTAS)
                .partitions(1)
                .replicas(replicas)
                .config(TopicConfig.RETENTION_MS_CONFIG, "2592000000") // 30 dias, las alertas se auditan
                .build();
    }

    @Bean
    ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        // si kafka esta caido el send se quedaba pegado 60s (default), con esto falla en 2s
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        // que el envio falle rapido en vez de quedarse 2 min en memoria reintentando solo,
        // asi si kafka esta caido el que reintenta es el outbox
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 4500);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
