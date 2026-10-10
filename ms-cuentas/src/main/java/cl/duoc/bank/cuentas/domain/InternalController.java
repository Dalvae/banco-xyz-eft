package cl.duoc.bank.cuentas.domain;

import static cl.duoc.bank.shared.DomainDtos.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

// solo para ms-pagos: pide un token client_credentials con el scope cuentas:operar.
// un token de usuario (web, movil, cajero) no tiene ese scope asi que no puede mover plata directo aca
@RestController
@RequestMapping("/internal")
@PreAuthorize("hasAuthority('SCOPE_cuentas:operar')")
public class InternalController {
    private final CuentasService service;

    public InternalController(CuentasService service) {
        this.service = service;
    }

    @PostMapping("/cuentas/{id}/debitos")
    public LegResult debitar(@PathVariable long id, @RequestBody LegRequest request) {
        return service.debitar(id, request);
    }

    @PostMapping("/cuentas/{id}/creditos")
    public LegResult acreditar(@PathVariable long id, @RequestBody LegRequest request) {
        return service.acreditar(id, request);
    }

    @PostMapping("/operaciones/{operationId}/reversa")
    public LegResult revertir(@PathVariable String operationId) {
        return service.revertir(operationId);
    }
}
