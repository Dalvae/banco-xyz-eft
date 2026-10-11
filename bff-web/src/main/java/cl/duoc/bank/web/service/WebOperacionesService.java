package cl.duoc.bank.web.service;

import static cl.duoc.bank.shared.DomainDtos.*;

import cl.duoc.bank.shared.BankClient;
import cl.duoc.bank.shared.HttpConfig;
import cl.duoc.bank.web.dto.WebDtos;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// las operaciones de escritura de la web. no llevan @Retry (son plata o cambios), solo circuit breaker:
// si el microservicio esta caido se responde 503 al tiro en vez de dejar el navegador colgado
@Service
public class WebOperacionesService {
    private static final String INCIERTO = "Servicio no disponible por ahora, el estado quedo incierto: consulta de nuevo con la misma Idempotency-Key";
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
    // en plata, si se corto a la mitad no sabemos si alcanzo a aplicarse: se consulta con la misma key
    Receipt transferirFallback(WebDtos.Transfer t, String key, String token, Throwable error) {
        throw HttpConfig.reenviarOFallar(error, INCIERTO);
    }

    Receipt pagarFallback(WebDtos.BillPayment p, String key, String token, Throwable error) {
        throw HttpConfig.reenviarOFallar(error, INCIERTO);
    }

    List<Receipt> operacionesFallback(String token, Throwable error) {
        throw caido(error);
    }

    Account cuentaFallback(WebDtos.NewAccount c, String token, Throwable error) {
        throw caido(error);
    }

    Account cerrarFallback(long id, String token, Throwable error) {
        throw caido(error);
    }

    Profile perfilFallback(WebDtos.ProfileChange c, String token, Throwable error) {
        throw caido(error);
    }

    // un 4xx de negocio (sin fondos, cuenta ajena) pasa tal cual, si el servicio no esta es 503
    private static ResponseStatusException caido(Throwable error) {
        return HttpConfig.reenviarOFallar(error, "Servicio no disponible por ahora, intenta en unos segundos");
    }
}
