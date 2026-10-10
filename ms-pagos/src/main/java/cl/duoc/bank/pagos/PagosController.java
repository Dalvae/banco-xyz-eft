package cl.duoc.bank.pagos;

import static cl.duoc.bank.shared.DomainDtos.OperationRequest;
import static cl.duoc.bank.shared.DomainDtos.Receipt;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

// cada operacion pide un scope distinto, asi el cajero puede retirar y depositar pero no transferir,
// y la web transfiere y paga pero no retira plata
@RestController
@RequestMapping("/pagos")
public class PagosController {
    private final PagosService service;

    public PagosController(PagosService service) {
        this.service = service;
    }

    @PostMapping("/retiros")
    @PreAuthorize("hasAuthority('SCOPE_atm:withdraw')")
    public Receipt retiro(@RequestBody OperationRequest req, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                          @AuthenticationPrincipal Jwt jwt) {
        return service.retirar(jwt.getSubject(), req.fromAccount(), req.amount(), key, canal(jwt));
    }

    @PostMapping("/depositos")
    @PreAuthorize("hasAuthority('SCOPE_atm:deposit')")
    public Receipt deposito(@RequestBody OperationRequest req, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                            @AuthenticationPrincipal Jwt jwt) {
        return service.depositar(jwt.getSubject(), req.toAccount(), req.amount(), key, canal(jwt));
    }

    @PostMapping("/transferencias")
    @PreAuthorize("hasAuthority('SCOPE_web:write')")
    public Receipt transferencia(@RequestBody OperationRequest req, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                 @AuthenticationPrincipal Jwt jwt) {
        return service.transferir(jwt.getSubject(), req.fromAccount(), req.toAccount(), req.amount(), key, canal(jwt));
    }

    @PostMapping("/servicios")
    @PreAuthorize("hasAuthority('SCOPE_web:write')")
    public Receipt pagoServicio(@RequestBody OperationRequest req, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                @AuthenticationPrincipal Jwt jwt) {
        return service.pagarServicio(jwt.getSubject(), req.fromAccount(), req.biller(), req.amount(), key, canal(jwt));
    }

    @GetMapping("/me/operaciones")
    @PreAuthorize("hasAnyAuthority('SCOPE_web:read','SCOPE_mobile:read')")
    public List<Receipt> operaciones(@AuthenticationPrincipal Jwt jwt) {
        return service.operaciones(jwt.getSubject());
    }

    // el canal sale de la audiencia del token (el client_id que lo pidio), no de algo que mande el cliente
    private static String canal(Jwt jwt) {
        List<String> aud = jwt.getAudience();
        String cliente = aud == null || aud.isEmpty() ? "" : aud.get(0);
        return switch (cliente) {
            case "bank-atm" -> "CAJERO";
            case "bank-web" -> "WEB";
            case "bank-mobile" -> "MOVIL";
            default -> "OTRO";
        };
    }
}
