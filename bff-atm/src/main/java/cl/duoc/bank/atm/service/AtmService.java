package cl.duoc.bank.atm.service;

import cl.duoc.bank.atm.dto.AtmDtos;
import cl.duoc.bank.shared.BankClient;
import cl.duoc.bank.shared.DomainDtos;
import cl.duoc.bank.shared.HttpConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AtmService {
    private final BankClient bankClient;

    public AtmService(BankClient bankClient) {
        this.bankClient = bankClient;
    }

    // el saldo sale de ms-cuentas
    @CircuitBreaker(name = "msCuentas", fallbackMethod = "balanceFallback")
    @Retry(name = "msCuentas")
    public AtmDtos.Balance balance(long accountId, String token) {
        DomainDtos.Account account = bankClient.balance(accountId, token);
        return new AtmDtos.Balance(account.maskedNumber(), account.currency(), account.balance());
    }

    // retiro y deposito van a ms-pagos. NO llevan @Retry desde el cajero, reintentar un POST de plata
    // lo decide ms-pagos (que si es idempotente por debajo), aca solo el circuit breaker
    @CircuitBreaker(name = "msPagos", fallbackMethod = "withdrawFallback")
    public AtmDtos.OperationResult withdraw(long accountId, AtmDtos.Withdrawal request, String idempotencyKey, String token) {
        DomainDtos.Receipt receipt = bankClient.withdraw(new DomainDtos.OperationRequest(accountId, null, null, request.amount()),
                idempotencyKey, token);
        return new AtmDtos.OperationResult(receipt.operationId(), accountId, receipt.amount(), receipt.balance(), receipt.status());
    }

    @CircuitBreaker(name = "msPagos", fallbackMethod = "depositFallback")
    public AtmDtos.OperationResult deposit(long accountId, AtmDtos.Deposit request, String idempotencyKey, String token) {
        DomainDtos.Receipt receipt = bankClient.deposit(new DomainDtos.OperationRequest(null, accountId, null, request.amount()),
                idempotencyKey, token);
        return new AtmDtos.OperationResult(receipt.operationId(), accountId, receipt.amount(), receipt.balance(), receipt.status());
    }

    // en el cajero no tiene sentido mostrar un saldo viejo, asi que el fallback responde 503
    AtmDtos.Balance balanceFallback(long accountId, String token, Throwable error) {
        throw sinConexion(error);
    }

    AtmDtos.OperationResult withdrawFallback(long accountId, AtmDtos.Withdrawal request,
            String idempotencyKey, String token, Throwable error) {
        throw sinConexion(error);
    }

    AtmDtos.OperationResult depositFallback(long accountId, AtmDtos.Deposit request,
            String idempotencyKey, String token, Throwable error) {
        throw sinConexion(error);
    }

    private static ResponseStatusException sinConexion(Throwable error) {
        return HttpConfig.reenviarOFallar(error, "Cajero sin conexion con el banco, intenta mas tarde");
    }
}
