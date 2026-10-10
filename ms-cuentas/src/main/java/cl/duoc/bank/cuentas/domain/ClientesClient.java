package cl.duoc.bank.cuentas.domain;

import cl.duoc.bank.shared.ApiException;
import cl.duoc.bank.shared.DomainDtos.Profile;
import cl.duoc.bank.shared.HttpConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

// antes de abrir una cuenta reviso en ms-clientes que el cliente exista (con el mismo token del usuario)
@Component
public class ClientesClient {
    private static final Logger LOG = LoggerFactory.getLogger(ClientesClient.class);
    private final RestClient clientes;

    public ClientesClient(@Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder) {
        this.clientes = builder.clone().baseUrl("http://ms-clientes")
                .defaultStatusHandler(HttpStatusCode::is4xxClientError, HttpConfig::reenviar4xx).build();
    }

    @CircuitBreaker(name = "msClientes", fallbackMethod = "sinClientes")
    @Retry(name = "msClientes")
    public Profile perfil(String token) {
        return clientes.get().uri("/clientes/me").headers(h -> h.setBearerAuth(token)).retrieve().body(Profile.class);
    }

    // si ms-clientes esta caido no abro la cuenta, prefiero un 503 a una cuenta de alguien que no existe
    Profile sinClientes(String token, Throwable error) {
        if (error instanceof ResponseStatusException e && e.getStatusCode().value() == 404) {
            throw new ApiException(HttpStatus.CONFLICT, "El cliente no esta registrado en ms-clientes");
        }
        if (error instanceof ResponseStatusException e) {
            throw e;
        }
        LOG.warn("ms-clientes no responde, no se puede abrir la cuenta: {}", error.toString());
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo validar el cliente, intenta en unos segundos");
    }
}
