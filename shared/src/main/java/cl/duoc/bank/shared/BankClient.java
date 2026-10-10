package cl.duoc.bank.shared;

import static cl.duoc.bank.shared.DomainDtos.Account;
import static cl.duoc.bank.shared.DomainDtos.MovementPage;
import static cl.duoc.bank.shared.DomainDtos.Profile;
import static cl.duoc.bank.shared.DomainDtos.WithdrawalReceipt;
import static cl.duoc.bank.shared.DomainDtos.WithdrawalRequest;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

// cliente http hacia bank-api, lo usan los 3 bff. el token del usuario se reenvia tal cual
@Component
public class BankClient {
    private final RestClient client;

    public BankClient(@Value("${bank.client.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                // los 4xx (403 cuenta ajena, 404, 409 sin fondos) se devuelven igual al cliente
                // los 5xx y errores de conexion siguen como excepcion y los cuenta el circuit breaker
                .defaultStatusHandler(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw new ResponseStatusException(response.getStatusCode());
                })
                .build();
    }

    public Profile profile(String token) {
        return client.get().uri("/internal/me/profile")
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(Profile.class);
    }

    public List<Account> accounts(String token) {
        return client.get().uri("/internal/me/accounts")
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(new ParameterizedTypeReference<List<Account>>() { });
    }

    public MovementPage movements(long id, int page, int size, String token) {
        return client.get().uri("/internal/accounts/{id}/movements?page={page}&size={size}", id, page, size)
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(MovementPage.class);
    }

    public Account balance(long id, String token) {
        return client.get().uri("/internal/accounts/{id}/balance", id)
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(Account.class);
    }

    public WithdrawalReceipt withdraw(long id, WithdrawalRequest request, String key, String token) {
        return client.post().uri("/internal/accounts/{id}/withdrawals", id)
                .headers(h -> {
                    h.setBearerAuth(token);
                    h.set("Idempotency-Key", key);
                })
                .body(request)
                .retrieve().body(WithdrawalReceipt.class);
    }
}
