package cl.duoc.bank.notificaciones;

import cl.duoc.bank.shared.Eventos.RetiroRealizado;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

// config del consumidor como en la clase: ConsumerFactory + ConcurrentKafkaListenerContainerFactory
@Configuration
public class KafkaConsumerConfig {
    public static final String GRUPO = "notificaciones";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // en los tests lo apago para que no intente conectarse a kafka
    @Value("${spring.kafka.listener.auto-startup:true}")
    private boolean autoStartup;

    @Bean
    ConsumerFactory<String, RetiroRealizado> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GRUPO);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        JsonDeserializer<RetiroRealizado> json = new JsonDeserializer<>(RetiroRealizado.class, false);
        // si llega algo que no es json lo salta y no se queda pegado
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new ErrorHandlingDeserializer<>(json));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, RetiroRealizado> kafkaListenerContainerFactory(
            ConsumerFactory<String, RetiroRealizado> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, RetiroRealizado> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setAutoStartup(autoStartup);
        // ack manual, se confirma despues de guardar
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
