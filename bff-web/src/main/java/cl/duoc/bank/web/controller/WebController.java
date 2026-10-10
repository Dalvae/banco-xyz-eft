package cl.duoc.bank.web.controller;

import cl.duoc.bank.web.dto.WebDtos;
import cl.duoc.bank.web.service.WebDashboardService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/web")
public class WebController {
    private final WebDashboardService service;

    public WebController(WebDashboardService service) {
        this.service = service;
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
}
