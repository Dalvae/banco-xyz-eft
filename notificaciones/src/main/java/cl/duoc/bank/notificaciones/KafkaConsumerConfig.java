package cl.duoc.bank.notificaciones;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.util.backoff.ExponentialBackOff;

// config del consumidor como en la clase: ConsumerFactory + ConcurrentKafkaListenerContainerFactory.
// ahora son 2 topicos con eventos distintos, asi que leo String y el converter arma el record segun
// el parametro del metodo del listener (en s8 era un JsonDeserializer fijo a RetiroRealizado)
@Configuration
public class KafkaConsumerConfig {
    public static final String GRUPO = "notificaciones";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // en los tests lo apago para que no intente conectarse a kafka
    @Value("${spring.kafka.listener.auto-startup:true}")
    private boolean autoStartup;

    @Bean
    ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GRUPO);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer());
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, KafkaOperations<?, ?> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setRecordMessageConverter(new StringJsonMessageConverter());
        factory.setAutoStartup(autoStartup);
        // ack manual, se confirma despues de guardar
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // si el listener falla (bd caida, evento mal formado) se reintenta con espera 1s, 2s, 4s... hasta ~1 min
        // y despues el mensaje se manda a <topico>.DLT para no trancar la particion. el KafkaTemplate es el de spring boot
        var dlt = new DeadLetterPublishingRecoverer(kafkaTemplate, (record, error) -> new TopicPartition(record.topic() + ".DLT", -1));
        var backoff = new ExponentialBackOff(1000, 2.0);
        backoff.setMaxInterval(15000);
        backoff.setMaxElapsedTime(60000);
        factory.setCommonErrorHandler(new DefaultErrorHandler(dlt, backoff));
        return factory;
    }
}
