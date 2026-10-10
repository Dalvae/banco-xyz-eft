package cl.duoc.bank.api.eventos;

import cl.duoc.bank.shared.Eventos;
import cl.duoc.bank.shared.Eventos.RetiroRealizado;
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
import org.springframework.kafka.support.serializer.JsonSerializer;

// config del productor armada como en la clase de la semana 8: ProducerFactory + KafkaTemplate + el topico
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // 3 particiones, 3 replicas. con min.insync=2 se puede caer un broker
    @Bean
    NewTopic topicoMovimientos() {
        return TopicBuilder.name(Eventos.TOPICO_MOVIMIENTOS)
                .partitions(3)
                .replicas(3)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000") // 7 dias
                .build();
    }

    @Bean
    ProducerFactory<String, RetiroRealizado> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        // si kafka esta caido el send se quedaba pegado 60s (default) con la cuenta bloqueada, con esto falla en 2s
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        // que el envio falle rapido en vez de quedarse 2 min en memoria reintentando solo,
        // asi si kafka esta caido el que reintenta es el outbox y no salen eventos repetidos
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 4500);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    KafkaTemplate<String, RetiroRealizado> kafkaTemplate(ProducerFactory<String, RetiroRealizado> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
