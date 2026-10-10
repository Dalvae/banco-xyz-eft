package cl.duoc.bank.notificaciones;

import java.util.Map;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// pausar y reanudar el consumidor como en la clase
@RestController
@RequestMapping("/api/kafka-listener")
@PreAuthorize("hasAuthority('SCOPE_notificaciones:admin')")
public class KafkaListenerController {
    private final KafkaListenerEndpointRegistry registry;

    public KafkaListenerController(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/pausar")
    public Map<String, String> pausar() {
        listener().pause();
        return estado();
    }

    @PostMapping("/reanudar")
    public Map<String, String> reanudar() {
        listener().resume();
        return estado();
    }

    @GetMapping("/estado")
    public Map<String, String> estado() {
        return Map.of("listener", MovimientosListener.ID, "estado", listener().isPauseRequested() ? "pausado" : "activo");
    }

    private MessageListenerContainer listener() {
        return registry.getListenerContainer(MovimientosListener.ID);
    }
}
