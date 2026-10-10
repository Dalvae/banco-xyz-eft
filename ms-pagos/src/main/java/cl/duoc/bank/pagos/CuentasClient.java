package cl.duoc.bank.pagos;

import static cl.duoc.bank.shared.DomainDtos.LegRequest;
import static cl.duoc.bank.shared.DomainDtos.LegResult;

import cl.duoc.bank.shared.HttpConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

// ms-pagos llama a ms-cuentas por eureka (http://ms-cuentas) con su propio token client_credentials,
// no con el del usuario: el scope cuentas:operar solo lo tiene este cliente
@Component
public class CuentasClient {
    private static final Logger LOG = LoggerFactory.getLogger(CuentasClient.class);
    private final RestClient cuentas;

    public CuentasClient(@Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
                         ClientRegistrationRepository registros, OAuth2AuthorizedClientService tokens) {
        // este manager funciona fuera de un request http (lo usa tambien el reconciliador que corre con @Scheduled)
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registros, tokens);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        var oauth = new OAuth2ClientHttpRequestInterceptor(manager);
        oauth.setClientRegistrationIdResolver(request -> "ms-pagos");
        oauth.setPrincipalResolver(request -> new AnonymousAuthenticationToken("ms-pagos", "ms-pagos",
                AuthorityUtils.createAuthorityList("ROLE_SERVICIO")));
        this.cuentas = builder.clone().baseUrl("http://ms-cuentas").requestInterceptor(oauth)
                .defaultStatusHandler(HttpStatusCode::is4xxClientError, HttpConfig::reenviar4xx).build();
    }

    // aca si reintento: el debito es idempotente por operationId, si llega 2 veces ms-cuentas lo aplica 1 sola
    @CircuitBreaker(name = "msCuentas", fallbackMethod = "debitarFallback")
    @Retry(name = "msCuentas")
    public LegResult debitar(long cuenta, LegRequest pierna) {
        return cuentas.post().uri("/internal/cuentas/{id}/debitos", cuenta).body(pierna).retrieve().body(LegResult.class);
    }

    @CircuitBreaker(name = "msCuentas", fallbackMethod = "acreditarFallback")
    @Retry(name = "msCuentas")
    public LegResult acreditar(long cuenta, LegRequest pierna) {
        return cuentas.post().uri("/internal/cuentas/{id}/creditos", cuenta).body(pierna).retrieve().body(LegResult.class);
    }

    @CircuitBreaker(name = "msCuentas", fallbackMethod = "revertirFallback")
    @Retry(name = "msCuentas")
    public LegResult revertir(String operationId) {
        return cuentas.post().uri("/internal/operaciones/{op}/reversa", operationId).retrieve().body(LegResult.class);
    }

    // fallbacks: un 4xx es respuesta de negocio (sin fondos, cuenta cerrada) y se devuelve tal cual.
    // lo demas es que ms-cuentas no esta, y eso lo maneja la saga
    LegResult debitarFallback(long cuenta, LegRequest pierna, Throwable error) {
        throw caido(error);
    }

    LegResult acreditarFallback(long cuenta, LegRequest pierna, Throwable error) {
        throw caido(error);
    }

    LegResult revertirFallback(String operationId, Throwable error) {
        throw caido(error);
    }

    private static RuntimeException caido(Throwable error) {
        if (error instanceof ResponseStatusException e) {
            return e;
        }
        LOG.warn("ms-cuentas no responde: {}", error.toString());
        return new CuentasNoDisponible(error);
    }
}
