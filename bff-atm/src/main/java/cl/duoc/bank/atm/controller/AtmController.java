package cl.duoc.bank.atm.controller;

import cl.duoc.bank.atm.dto.AtmDtos;
import cl.duoc.bank.atm.service.AtmService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/atm/cuentas")
public class AtmController {
    private final AtmService service;

    public AtmController(AtmService service) {
        this.service = service;
    }

    @GetMapping("/{id}/saldo")
    @PreAuthorize("hasAuthority('SCOPE_atm:balance')")
    public AtmDtos.Balance balance(@PathVariable long id,
            @AuthenticationPrincipal Jwt jwt) {
        return service.balance(id, jwt.getTokenValue());
    }

    @PostMapping("/{id}/retiros")
    @PreAuthorize("hasAuthority('SCOPE_atm:withdraw')")
    public AtmDtos.WithdrawalResult withdraw(@PathVariable long id,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody AtmDtos.Withdrawal request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.withdraw(id, request, idempotencyKey, jwt.getTokenValue());
    }
}
