package cl.duoc.bank.shared;

import static cl.duoc.bank.shared.DomainDtos.Account;
import static cl.duoc.bank.shared.DomainDtos.MovementPage;
import static cl.duoc.bank.shared.DomainDtos.OpenAccount;
import static cl.duoc.bank.shared.DomainDtos.OperationRequest;
import static cl.duoc.bank.shared.DomainDtos.Profile;
import static cl.duoc.bank.shared.DomainDtos.ProfileUpdate;
import static cl.duoc.bank.shared.DomainDtos.Receipt;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// cliente http hacia los 3 microservicios, lo usan los 3 bff. el token del usuario se reenvia tal cual
// en s8 era un solo bank-api, ahora cada cosa va a su servicio y la url es el nombre en eureka
@Component
public class BankClient {
    private final RestClient cuentas;
    private final RestClient pagos;
    private final RestClient clientes;

    public BankClient(@Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
                      @Value("${bank.services.cuentas:http://ms-cuentas}") String cuentasUrl,
                      @Value("${bank.services.pagos:http://ms-pagos}") String pagosUrl,
                      @Value("${bank.services.clientes:http://ms-clientes}") String clientesUrl) {
        // clone() porque el builder es uno solo y baseUrl lo modifica
        this.cuentas = cliente(builder, cuentasUrl);
        this.pagos = cliente(builder, pagosUrl);
        this.clientes = cliente(builder, clientesUrl);
    }

    private static RestClient cliente(RestClient.Builder builder, String url) {
        return builder.clone().baseUrl(url).defaultStatusHandler(HttpStatusCode::is4xxClientError, HttpConfig::reenviar4xx).build();
    }

    // ---- ms-clientes
    public Profile profile(String token) {
        return clientes.get().uri("/clientes/me")
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(Profile.class);
    }

    public Profile updateProfile(ProfileUpdate update, String token) {
        return clientes.put().uri("/clientes/me")
                .headers(h -> h.setBearerAuth(token))
                .body(update)
                .retrieve().body(Profile.class);
    }

    // ---- ms-cuentas
    public List<Account> accounts(String token) {
        return cuentas.get().uri("/cuentas/me")
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(new ParameterizedTypeReference<List<Account>>() { });
    }

    public MovementPage movements(long id, int page, int size, String token) {
        return cuentas.get().uri("/cuentas/{id}/movimientos?page={page}&size={size}", id, page, size)
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(MovementPage.class);
    }

    public Account balance(long id, String token) {
        return cuentas.get().uri("/cuentas/{id}/saldo", id)
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(Account.class);
    }

    public Account openAccount(OpenAccount request, String token) {
        return cuentas.post().uri("/cuentas")
                .headers(h -> h.setBearerAuth(token))
                .body(request)
                .retrieve().body(Account.class);
    }

    public Account closeAccount(long id, String token) {
        return cuentas.post().uri("/cuentas/{id}/cierre", id)
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(Account.class);
    }

    // ---- ms-pagos. todos llevan Idempotency-Key, si se repite la misma key ms-pagos devuelve el mismo comprobante
    public Receipt withdraw(OperationRequest request, String key, String token) {
        return operacion("/pagos/retiros", request, key, token);
    }

    public Receipt deposit(OperationRequest request, String key, String token) {
        return operacion("/pagos/depositos", request, key, token);
    }

    public Receipt transfer(OperationRequest request, String key, String token) {
        return operacion("/pagos/transferencias", request, key, token);
    }

    public Receipt payBill(OperationRequest request, String key, String token) {
        return operacion("/pagos/servicios", request, key, token);
    }

    public List<Receipt> operations(String token) {
        return pagos.get().uri("/pagos/me/operaciones")
                .headers(h -> h.setBearerAuth(token))
                .retrieve().body(new ParameterizedTypeReference<List<Receipt>>() { });
    }

    private Receipt operacion(String uri, OperationRequest request, String key, String token) {
        return pagos.post().uri(uri)
                .headers(h -> {
                    h.setBearerAuth(token);
                    h.set("Idempotency-Key", key);
                })
                .body(request)
                .retrieve().body(Receipt.class);
    }
}
