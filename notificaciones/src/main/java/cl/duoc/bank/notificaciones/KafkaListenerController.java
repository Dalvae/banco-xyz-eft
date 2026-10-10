package cl.duoc.bank.notificaciones;

import java.util.Map;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

// pausar y reanudar los consumidores como en la clase. id = transaccionesListener o alertasListener
@RestController
@RequestMapping("/api/notificaciones/listeners")
@PreAuthorize("hasAuthority('SCOPE_notificaciones:admin')")
public class KafkaListenerController {
    private final KafkaListenerEndpointRegistry registry;

    public KafkaListenerController(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/{id}/pausar")
    public Map<String, String> pausar(@PathVariable String id) {
        listener(id).pause();
        return estado(id);
    }

    @PostMapping("/{id}/reanudar")
    public Map<String, String> reanudar(@PathVariable String id) {
        listener(id).resume();
        return estado(id);
    }

    @GetMapping("/{id}")
    public Map<String, String> estado(@PathVariable String id) {
        return Map.of("listener", id, "estado", listener(id).isPauseRequested() ? "pausado" : "activo");
    }

    private MessageListenerContainer listener(String id) {
        MessageListenerContainer container = registry.getListenerContainer(id);
        if (container == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe el listener " + id);
        return container;
    }
}
