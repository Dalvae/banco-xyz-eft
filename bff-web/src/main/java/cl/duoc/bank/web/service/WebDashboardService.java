package cl.duoc.bank.web.service;

import cl.duoc.bank.shared.BankClient;
import cl.duoc.bank.shared.DomainDtos;
import cl.duoc.bank.shared.Llamadas;
import cl.duoc.bank.web.dto.WebDtos;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WebDashboardService {
    private static final Logger LOG = LoggerFactory.getLogger(WebDashboardService.class);
    private static final int DASHBOARD_MOVEMENTS = 20;
    private final BankClient bankClient;
    private final Executor aggregationExecutor;

    public WebDashboardService(BankClient bankClient,
            @Qualifier("aggregationExecutor") Executor aggregationExecutor) {
        this.bankClient = bankClient;
        this.aggregationExecutor = aggregationExecutor;
    }

    @CircuitBreaker(name = "msCuentas", fallbackMethod = "dashboardFallback")
    @Retry(name = "msCuentas")
    public WebDtos.Dashboard dashboard(String token) {
        // perfil (ms-clientes) y cuentas (ms-cuentas) no dependen entre si, asi que los pido al mismo tiempo
        CompletableFuture<DomainDtos.Profile> profileCall = CompletableFuture.supplyAsync(
                () -> bankClient.profile(token), aggregationExecutor);
        CompletableFuture<List<DomainDtos.Account>> accountsCall = CompletableFuture.supplyAsync(
                () -> bankClient.accounts(token), aggregationExecutor);
        Llamadas.esperarTodas(profileCall, accountsCall);

        DomainDtos.Profile profile = profileCall.join();
        List<WebDtos.Account> accounts = accountsCall.join().stream()
                .map(account -> toAccount(account,
                        bankClient.movements(account.id(), 0, DASHBOARD_MOVEMENTS, token)))
                .toList();
        return new WebDtos.Dashboard(new WebDtos.Profile(profile.id(), profile.name(), profile.email(), profile.phone(),
                profile.segment()), accounts, false, null);
    }

    @CircuitBreaker(name = "msCuentas", fallbackMethod = "movementsFallback")
    @Retry(name = "msCuentas")
    public WebDtos.MovementPage movements(long accountId, int page, int size, String token) {
        return toMovementPage(bankClient.movements(accountId, page, size, token));
    }

    // fallback: si los microservicios estan caidos el dashboard sale vacio con degraded=true en vez de un 500
    WebDtos.Dashboard dashboardFallback(String token, Throwable error) {
        if (error instanceof ResponseStatusException e) {
            throw e; // un 403 o 404 no es caida del servicio
        }
        LOG.warn("ms-cuentas/ms-clientes no responde, dashboard degradado: {}", error.toString());
        return new WebDtos.Dashboard(null, List.of(), true, "No pudimos conectar con el banco, intenta en unos segundos");
    }

    WebDtos.MovementPage movementsFallback(long accountId, int page, int size, String token, Throwable error) {
        if (error instanceof ResponseStatusException e) {
            throw e;
        }
        LOG.warn("ms-cuentas no responde al pedir movimientos: {}", error.toString());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Movimientos no disponibles por ahora");
    }

    private WebDtos.Account toAccount(DomainDtos.Account account, DomainDtos.MovementPage movements) {
        return new WebDtos.Account(account.id(), account.displayName(), account.maskedNumber(),
                account.currency(), account.balance(), account.status(), account.origin(), toMovementPage(movements));
    }

    private WebDtos.MovementPage toMovementPage(DomainDtos.MovementPage page) {
        List<WebDtos.Movement> items = page.items().stream().map(this::toMovement).toList();
        return new WebDtos.MovementPage(items, page.page(), page.size(), page.total());
    }

    private WebDtos.Movement toMovement(DomainDtos.Movement movement) {
        return new WebDtos.Movement(movement.id(), movement.date(), movement.type(), movement.amount(),
                movement.description(), movement.source());
    }
}
