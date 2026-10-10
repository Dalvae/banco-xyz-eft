package cl.duoc.bank.notificaciones;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notificaciones")
public class NotificacionesController {
    private final JdbcTemplate jdbc;

    public NotificacionesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Notificacion(long id, String eventoId, String topico, String tipo, Long cuentaId, String titular,
                               String mensaje, Timestamp recibidoEn) { }

    // se consulta con un token client_credentials del cliente ops-notificaciones
    // ?topico=banco.alertas-seguridad para ver solo las alertas
    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_notificaciones:read')")
    public List<Notificacion> ultimas(@RequestParam(required = false) String topico) {
        String sql = "SELECT id,evento_id,topico,tipo,cuenta_id,titular,mensaje,recibido_en FROM notificacion "
                + (topico == null ? "" : "WHERE topico=? ") + "ORDER BY id DESC LIMIT 20";
        Object[] params = topico == null ? new Object[0] : new Object[] {topico};
        return jdbc.query(sql, (rs, n) -> new Notificacion(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, Long.class), rs.getString(6), rs.getString(7), rs.getTimestamp(8)), params);
    }
}
