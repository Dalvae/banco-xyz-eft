package cl.duoc.bank.atm.service;

import cl.duoc.bank.atm.dto.AtmDtos;
import cl.duoc.bank.shared.BankClient;
import cl.duoc.bank.shared.DomainDtos;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AtmService {
    private static final Logger LOG = LoggerFactory.getLogger(AtmService.class);
    private final BankClient bankClient;

    public AtmService(BankClient bankClient) {
        this.bankClient = bankClient;
    }

    @CircuitBreaker(name = "bankApi", fallbackMethod = "balanceFallback")
    @Retry(name = "bankApi")
    public AtmDtos.Balance balance(long accountId, String token) {
        DomainDtos.Account account = bankClient.balance(accountId, token);
        return new AtmDtos.Balance(account.maskedNumber(), account.currency(), account.balance());
    }

    // el retiro NO lleva @Retry, reintentar un POST de plata puede cobrar 2 veces
    @CircuitBreaker(name = "bankApi", fallbackMethod = "withdrawFallback")
    public AtmDtos.WithdrawalResult withdraw(long accountId, AtmDtos.Withdrawal request,
            String idempotencyKey, String token) {
        DomainDtos.WithdrawalReceipt receipt = bankClient.withdraw(accountId,
                new DomainDtos.WithdrawalRequest(request.amount()), idempotencyKey, token);
        return new AtmDtos.WithdrawalResult(receipt.operationId(), receipt.accountId(),
                receipt.amount(), receipt.balance(), receipt.currency());
    }

    // en el cajero no tiene sentido mostrar un saldo viejo, asi que el fallback responde 503
    AtmDtos.Balance balanceFallback(long accountId, String token, Throwable error) {
        throw sinConexion(error);
    }

    AtmDtos.WithdrawalResult withdrawFallback(long accountId, AtmDtos.Withdrawal request,
            String idempotencyKey, String token, Throwable error) {
        throw sinConexion(error);
    }

    private static ResponseStatusException sinConexion(Throwable error) {
        if (error instanceof ResponseStatusException e) {
            return e;
        }
        LOG.warn("bank-api no responde desde el cajero: {}", error.toString());
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cajero sin conexion con el banco, intenta mas tarde");
    }
}
