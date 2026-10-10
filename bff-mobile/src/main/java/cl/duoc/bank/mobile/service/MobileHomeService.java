package cl.duoc.bank.mobile.service;

import cl.duoc.bank.mobile.dto.MobileDtos;
import cl.duoc.bank.shared.BankClient;
import cl.duoc.bank.shared.DomainDtos;
import cl.duoc.bank.shared.Llamadas;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MobileHomeService {
    private static final Logger LOG = LoggerFactory.getLogger(MobileHomeService.class);
    private static final int MAX_MOVEMENTS = 5;
    private final BankClient bankClient;
    private final Executor aggregationExecutor;

    public MobileHomeService(BankClient bankClient,
            @Qualifier("mobileAggregationExecutor") Executor aggregationExecutor) {
        this.bankClient = bankClient;
        this.aggregationExecutor = aggregationExecutor;
    }

    @CircuitBreaker(name = "msCuentas", fallbackMethod = "homeFallback")
    @Retry(name = "msCuentas")
    public MobileDtos.Home home(String token) {
        CompletableFuture<DomainDtos.Profile> profileCall = CompletableFuture.supplyAsync(
                () -> bankClient.profile(token), aggregationExecutor);
        CompletableFuture<List<DomainDtos.Account>> accountsCall = CompletableFuture.supplyAsync(
                () -> bankClient.accounts(token), aggregationExecutor);
        Llamadas.esperarTodas(profileCall, accountsCall);

        DomainDtos.Profile profile = profileCall.join();
        List<MobileDtos.AccountSummary> accounts = accountsCall.join().stream()
                .map(account -> toSummary(account,
                        bankClient.movements(account.id(), 0, MAX_MOVEMENTS, token)))
                .toList();
        return new MobileDtos.Home(profile.name(), accounts, false, null);
    }

    // si ms-cuentas o ms-clientes estan abajo la app muestra el inicio vacio con un aviso
    MobileDtos.Home homeFallback(String token, Throwable error) {
        if (error instanceof ResponseStatusException e) {
            throw e;
        }
        LOG.warn("microservicios no responden, inicio movil degradado: {}", error.toString());
        return new MobileDtos.Home(null, List.of(), true, "Servicio temporalmente no disponible");
    }

    private MobileDtos.AccountSummary toSummary(DomainDtos.Account account,
            DomainDtos.MovementPage page) {
        List<MobileDtos.MovementSummary> movements = page.items().stream()
                .limit(MAX_MOVEMENTS)
                .map(this::toSummary)
                .toList();
        return new MobileDtos.AccountSummary(account.id(), account.displayName(),
                account.maskedNumber(), account.currency(), account.balance(), movements);
    }

    private MobileDtos.MovementSummary toSummary(DomainDtos.Movement movement) {
        return new MobileDtos.MovementSummary(movement.id(), movement.date(), movement.type(),
                movement.amount());
    }
}
