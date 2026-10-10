package cl.duoc.bank.clientes;

import static cl.duoc.bank.shared.DomainDtos.Profile;
import static cl.duoc.bank.shared.DomainDtos.ProfileUpdate;

import cl.duoc.bank.shared.ApiException;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

// el cliente siempre es el "sub" del token, asi nadie puede ver ni cambiar el perfil de otro
@RestController
@RequestMapping("/clientes")
public class ClientesController {
    private static final Logger LOG = LoggerFactory.getLogger(ClientesController.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern FONO = Pattern.compile("^\\+?[0-9]{8,15}$");
    private final JdbcTemplate jdbc;

    public ClientesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyAuthority('SCOPE_web:read','SCOPE_mobile:read','SCOPE_web:write')")
    public Profile perfil(@AuthenticationPrincipal Jwt jwt) {
        return buscar(jwt.getSubject());
    }

    // solo se cambian los datos de contacto y el nombre, el segmento lo define el banco
    @PutMapping("/me")
    @PreAuthorize("hasAuthority('SCOPE_web:write')")
    @Transactional
    public Profile actualizar(@RequestBody ProfileUpdate cambios, @AuthenticationPrincipal Jwt jwt) {
        Profile actual = buscar(jwt.getSubject());
        String nombre = cambios.name() == null ? actual.name() : cambios.name().trim();
        String email = cambios.email() == null ? actual.email() : cambios.email().trim();
        String fono = cambios.phone() == null ? actual.phone() : cambios.phone().trim();
        if (nombre.isEmpty() || nombre.length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Nombre obligatorio (max 120)");
        }
        if (email != null && !EMAIL.matcher(email).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Email invalido");
        }
        if (fono != null && !FONO.matcher(fono).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Telefono invalido");
        }
        jdbc.update("UPDATE customer_profile SET display_name=?, email=?, phone=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                nombre, email, fono, actual.id());
        LOG.info("Perfil actualizado cliente={}", actual.id());
        return buscar(actual.id());
    }

    private Profile buscar(String id) {
        List<Profile> perfiles = jdbc.query("SELECT id,display_name,email,phone,segment FROM customer_profile WHERE id=?",
                (rs, n) -> new Profile(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), id);
        return perfiles.stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Cliente no encontrado"));
    }
}
