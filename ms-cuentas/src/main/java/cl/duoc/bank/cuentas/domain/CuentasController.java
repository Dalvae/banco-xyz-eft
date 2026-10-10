package cl.duoc.bank.cuentas.domain;

import static cl.duoc.bank.shared.DomainDtos.*;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

// lo que usan los bff con el token del usuario
@RestController
@RequestMapping("/cuentas")
public class CuentasController {
    private static final Logger LOG = LoggerFactory.getLogger(CuentasController.class);
    private final CuentasService service;
    private final ClientesClient clientes;
    // para ver en los logs que replica contesto cuando hay mas de una
    @Value("${HOSTNAME:local}")
    private String instancia;

    public CuentasController(CuentasService service, ClientesClient clientes) {
        this.service = service;
        this.clientes = clientes;
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyAuthority('SCOPE_web:read','SCOPE_mobile:read')")
    public List<Account> accounts(@AuthenticationPrincipal Jwt jwt) {
        return service.accounts(jwt.getSubject());
    }

    @GetMapping("/{id}/movimientos")
    @PreAuthorize("hasAnyAuthority('SCOPE_web:read','SCOPE_mobile:read')")
    public MovementPage movements(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt jwt) {
        // el auth-server manda el scope como lista
        boolean mobile = jwt.getClaimAsStringList("scope").contains("mobile:read");
        return service.movements(id, page, size, jwt.getSubject(), mobile);
    }

    @GetMapping("/{id}/saldo")
    @PreAuthorize("hasAnyAuthority('SCOPE_web:read','SCOPE_mobile:read','SCOPE_atm:balance')")
    public Account balance(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        LOG.info("saldo cuenta={} atendido por instancia={}", id, instancia);
        return service.balance(id, jwt.getSubject());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SCOPE_web:write')")
    public Account abrir(@RequestBody OpenAccount request, @AuthenticationPrincipal Jwt jwt) {
        clientes.perfil(jwt.getTokenValue()); // si no existe o ms-clientes esta caido tira error
        return service.abrir(jwt.getSubject(), request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_web:write')")
    public Account renombrar(@PathVariable long id, @RequestBody OpenAccount request, @AuthenticationPrincipal Jwt jwt) {
        return service.renombrar(id, jwt.getSubject(), request == null ? null : request.displayName());
    }

    @PostMapping("/{id}/cierre")
    @PreAuthorize("hasAuthority('SCOPE_web:write')")
    public Account cerrar(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return service.cerrar(id, jwt.getSubject());
    }
}
