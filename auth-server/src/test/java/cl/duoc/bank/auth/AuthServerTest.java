package cl.duoc.bank.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"bank.auth.demo-password=test", "bank.auth.ops-secret=ops-secret", "bank.auth.pagos-secret=pagos-secret"})
@AutoConfigureMockMvc
class AuthServerTest {
    @Autowired MockMvc mvc;

    @Test
    void publicaMetadataYJwks() throws Exception {
        mvc.perform(get("/.well-known/openid-configuration")).andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://auth-server:9000"));
        mvc.perform(get("/oauth2/jwks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"));
    }

    @Test
    void clientCredentialsDaToken() throws Exception {
        mvc.perform(post("/oauth2/token").with(httpBasic("ops-notificaciones", "ops-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "notificaciones:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty());
        // con el secreto malo no
        mvc.perform(post("/oauth2/token").with(httpBasic("ops-notificaciones", "otra"))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void msPagosSoloPuedePedirCuentasOperar() throws Exception {
        mvc.perform(post("/oauth2/token").with(httpBasic("ms-pagos", "pagos-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "cuentas:operar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("cuentas:operar"));
        mvc.perform(post("/oauth2/token").with(httpBasic("ms-pagos", "pagos-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "web:read"))
                .andExpect(status().isBadRequest());
    }
}
