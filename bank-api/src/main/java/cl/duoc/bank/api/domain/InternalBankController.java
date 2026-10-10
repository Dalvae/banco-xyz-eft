package cl.duoc.bank.api.domain;

import static cl.duoc.bank.shared.DomainDtos.*;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal")
public class InternalBankController {
    private final BankingService service;

    public InternalBankController(BankingService service) { this.service = service; }

    @GetMapping("/me/profile")
    @PreAuthorize("hasAnyAuthority('SCOPE_web:read','SCOPE_mobile:read')")
    public Profile profile(@AuthenticationPrincipal Jwt jwt) { return service.profile(jwt.getSubject()); }

    @GetMapping("/me/accounts")
    @PreAuthorize("hasAnyAuthority('SCOPE_web:read','SCOPE_mobile:read')")
    public List<Account> accounts(@AuthenticationPrincipal Jwt jwt) { return service.accounts(jwt.getSubject()); }

    @GetMapping("/accounts/{id}/movements")
    @PreAuthorize("hasAnyAuthority('SCOPE_web:read','SCOPE_mobile:read')")
    public MovementPage movements(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt jwt) {
        // el auth-server manda el scope como lista
        boolean mobile = jwt.getClaimAsStringList("scope").contains("mobile:read");
        return service.movements(id, page, size, jwt.getSubject(), mobile);
    }

    @GetMapping("/accounts/{id}/balance")
    @PreAuthorize("hasAnyAuthority('SCOPE_web:read','SCOPE_mobile:read','SCOPE_atm:balance')")
    public Account balance(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return service.balance(id, jwt.getSubject());
    }

    @PostMapping("/accounts/{id}/withdrawals")
    @PreAuthorize("hasAuthority('SCOPE_atm:withdraw')")
    public WithdrawalReceipt withdraw(@PathVariable long id, @RequestBody WithdrawalRequest request,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                      @AuthenticationPrincipal Jwt jwt) {
        return service.withdraw(id, request == null ? null : request.amount(), key, jwt.getSubject());
    }
}
