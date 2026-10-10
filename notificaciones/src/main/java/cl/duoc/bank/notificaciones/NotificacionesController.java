package cl.duoc.bank.notificaciones;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificacionesController {
    private final JdbcTemplate jdbc;

    public NotificacionesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // se consulta con un token client_credentials del cliente ops-notificaciones
    @GetMapping("/api/notificaciones")
    @PreAuthorize("hasAuthority('SCOPE_notificaciones:read')")
    public List<Map<String, Object>> ultimas() {
        return jdbc.queryForList("SELECT * FROM notificacion ORDER BY id DESC LIMIT 10");
    }
}
