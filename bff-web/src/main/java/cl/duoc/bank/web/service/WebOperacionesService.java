package cl.duoc.bank.web.service;

import static cl.duoc.bank.shared.DomainDtos.*;

import cl.duoc.bank.shared.BankClient;
import cl.duoc.bank.web.dto.WebDtos;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// las operaciones de escritura de la web. no llevan @Retry (son plata o cambios), solo circuit breaker:
// si el microservicio esta caido se responde 503 al tiro en vez de dejar el navegador colgado
@Service
public class WebOperacionesService {
    private static final Logger LOG = LoggerFactory.getLogger(WebOperacionesService.class);
    private final BankClient bankClient;

    public WebOperacionesService(BankClient bankClient) {
        this.bankClient = bankClient;
    }

    @CircuitBreaker(name = "msPagos", fallbackMethod = "transferirFallback")
    public Receipt transferir(WebDtos.Transfer t, String key, String token) {
        return bankClient.transfer(new OperationRequest(t.fromAccount(), t.toAccount(), null, t.amount()), key, token);
    }

    @CircuitBreaker(name = "msPagos", fallbackMethod = "pagarFallback")
    public Receipt pagar(WebDtos.BillPayment p, String key, String token) {
        return bankClient.payBill(new OperationRequest(p.fromAccount(), null, p.biller(), p.amount()), key, token);
    }

    @CircuitBreaker(name = "msPagos", fallbackMethod = "operacionesFallback")
    @Retry(name = "msPagos")
    public List<Receipt> operaciones(String token) {
        return bankClient.operations(token);
    }

    @CircuitBreaker(name = "msCuentas", fallbackMethod = "cuentaFallback")
    public Account abrirCuenta(WebDtos.NewAccount c, String token) {
        return bankClient.openAccount(new OpenAccount(c.displayName(), c.currency()), token);
    }

    @CircuitBreaker(name = "msCuentas", fallbackMethod = "cerrarFallback")
    public Account cerrarCuenta(long id, String token) {
        return bankClient.closeAccount(id, token);
    }

    @CircuitBreaker(name = "msClientes", fallbackMethod = "perfilFallback")
    public Profile actualizarPerfil(WebDtos.ProfileChange c, String token) {
        return bankClient.updateProfile(new ProfileUpdate(c.name(), c.email(), c.phone()), token);
    }

    // resilience4j busca el fallback con los mismos parametros + la excepcion, por eso hay uno por metodo
    Receipt transferirFallback(WebDtos.Transfer t, String key, String token, Throwable error) {
        throw caido("ms-pagos", error);
    }

    Receipt pagarFallback(WebDtos.BillPayment p, String key, String token, Throwable error) {
        throw caido("ms-pagos", error);
    }

    List<Receipt> operacionesFallback(String token, Throwable error) {
        throw caido("ms-pagos", error);
    }

    Account cuentaFallback(WebDtos.NewAccount c, String token, Throwable error) {
        throw caido("ms-cuentas", error);
    }

    Account cerrarFallback(long id, String token, Throwable error) {
        throw caido("ms-cuentas", error);
    }

    Profile perfilFallback(WebDtos.ProfileChange c, String token, Throwable error) {
        throw caido("ms-clientes", error);
    }

    private static ResponseStatusException caido(String servicio, Throwable error) {
        if (error instanceof ResponseStatusException e) {
            return e; // 4xx de negocio (sin fondos, cuenta ajena) pasa tal cual
        }
        LOG.warn("{} no responde desde la web: {}", servicio, error.toString());
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible por ahora, no se hizo ningun cambio");
    }
}
