package cl.duoc.bank.gateway;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// si un bff entero no responde (o no hay ninguna instancia en eureka) el circuit breaker del gateway manda aca.
// los bff ya tienen su propio fallback cuando el que falla es un microservicio, esto es para cuando cae el bff
@RestController
public class FallbackController {

    @RequestMapping("/fallback/{canal}")
    ResponseEntity<Map<String, Object>> fallback(@PathVariable String canal) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "canal", canal,
                "degraded", true,
                "detail", "El servicio de " + canal + " no esta disponible, intenta en unos segundos"));
    }
}
