package cl.duoc.bank.web.controller;

import static cl.duoc.bank.shared.DomainDtos.Account;
import static cl.duoc.bank.shared.DomainDtos.Profile;
import static cl.duoc.bank.shared.DomainDtos.Receipt;

import cl.duoc.bank.web.dto.WebDtos;
import cl.duoc.bank.web.service.WebDashboardService;
import cl.duoc.bank.web.service.WebOperacionesService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

// bff web: el mas completo. lectura con web:read y todo lo que cambia algo con web:write
@RestController
@RequestMapping("/api/web")
public class WebController {
    private final WebDashboardService service;
    private final WebOperacionesService operaciones;

    public WebController(WebDashboardService service, WebOperacionesService operaciones) {
        this.service = service;
        this.operaciones = operaciones;
    }

    @GetMapping("/me/dashboard")
    @PreAuthorize("hasAuthority('SCOPE_web:read')")
    public WebDtos.Dashboard dashboard(@AuthenticationPrincipal Jwt jwt) {
        return service.dashboard(jwt.getTokenValue());
    }

    @GetMapping("/cuentas/{id}/movimientos")
    @PreAuthorize("hasAuthority('SCOPE_web:read')")
    public WebDtos.MovementPage movements(@PathVariable long id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal Jwt jwt) {
        return service.movements(id, page, size, jwt.getTokenValue());
    }

    @GetMapping("/me/operaciones")
    @PreAuthorize("hasAuthority('SCOPE_web:read')")
    public List<Receipt> operaciones(@AuthenticationPrincipal Jwt jwt) {
        return operaciones.operaciones(jwt.getTokenValue());
    }

    @PostMapping("/transferencias")
    @PreAuthorize("hasAuthority('SCOPE_web:write')")
    public Receipt transferir(@RequestHeader("Idempotency-Key") @NotBlank String key,
            @Valid @RequestBody WebDtos.Transfer request, @AuthenticationPrincipal Jwt jwt) {
        return operaciones.transferir(request, key, jwt.getTokenValue());
    }

    @PostMapping("/pagos")
    @PreAuthorize("hasAuthority('SCOPE_web:write')")
    public Receipt pagar(@RequestHeader("Idempotency-Key") @NotBlank String key,
            @Valid @RequestBody WebDtos.BillPayment request, @AuthenticationPrincipal Jwt jwt) {
        return operaciones.pagar(request, key, jwt.getTokenValue());
    }

    @PostMapping("/cuentas")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SCOPE_web:write')")
    public Account abrirCuenta(@Valid @RequestBody WebDtos.NewAccount request, @AuthenticationPrincipal Jwt jwt) {
        return operaciones.abrirCuenta(request, jwt.getTokenValue());
    }

    @PostMapping("/cuentas/{id}/cierre")
    @PreAuthorize("hasAuthority('SCOPE_web:write')")
    public Account cerrarCuenta(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return operaciones.cerrarCuenta(id, jwt.getTokenValue());
    }

    @PutMapping("/me/perfil")
    @PreAuthorize("hasAuthority('SCOPE_web:write')")
    public Profile actualizarPerfil(@Valid @RequestBody WebDtos.ProfileChange request, @AuthenticationPrincipal Jwt jwt) {
        return operaciones.actualizarPerfil(request, jwt.getTokenValue());
    }
}
