package cl.duoc.bank.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.duoc.bank.shared.BankClient;
import cl.duoc.bank.shared.DomainDtos;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest
@AutoConfigureMockMvc
class WebControllerTest {
    @Autowired MockMvc mvc;
    @Autowired CircuitBreakerRegistry breakers;
    @MockitoBean BankClient bankClient;

    @Test
    void dashboardConPerfilCuentasYMovimientos() throws Exception {
        when(bankClient.profile("web-token")).thenReturn(new DomainDtos.Profile("demo", "Demo", "demo@bancoxyz.cl", null, "PERSONAS"));
        when(bankClient.accounts("web-token")).thenReturn(List.of(new DomainDtos.Account(101, "demo",
                "Cuenta corriente", "****0101", "CLP", new BigDecimal("1000000"), "ABIERTA", "fixture")));
        when(bankClient.movements(101, 0, 20, "web-token")).thenReturn(new DomainDtos.MovementPage(List.of(
                new DomainDtos.Movement(1, 101, "2026-09-01", "compra", new BigDecimal("1000"), "Compra", "legacy")), 0, 20, 1));

        mvc.perform(get("/api/web/me/dashboard").with(webToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.id").value("demo"))
                .andExpect(jsonPath("$.accounts[0].movements.items[0].description").value("Compra"))
                .andExpect(jsonPath("$.degraded").value(false));
    }

    @Test
    void conElCircuitoAbiertoRespondeElFallback() throws Exception {
        CircuitBreaker breaker = breakers.circuitBreaker("msCuentas");
        breaker.transitionToOpenState();
        try {
            mvc.perform(get("/api/web/me/dashboard").with(webToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.degraded").value(true));
            verifyNoInteractions(bankClient);
        } finally {
            breaker.reset();
        }
    }

    @Test
    void sinTokenOConOtroScopeNoEntra() throws Exception {
        mvc.perform(get("/api/web/me/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/web/me/dashboard").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_mobile:read"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(bankClient);
    }

    @Test
    void transferenciaPideWriteYSiMsPagosCaeResponde503() throws Exception {
        String body = "{\"fromAccount\":101,\"toAccount\":102,\"amount\":5000}";
        mvc.perform(post("/api/web/transferencias").with(webToken()).header("Idempotency-Key", "t-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        when(bankClient.transfer(any(), eq("t-1"), eq("web-token"))).thenReturn(new DomainDtos.Receipt("op-1", "TRANSFERENCIA",
                101L, 102L, new BigDecimal("5000"), new BigDecimal("995000"), "COMPLETADA", null));
        mvc.perform(post("/api/web/transferencias").with(webWrite()).header("Idempotency-Key", "t-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETADA"));

        when(bankClient.transfer(any(), eq("t-2"), eq("web-token"))).thenThrow(new ResourceAccessException("Connection refused"));
        mvc.perform(post("/api/web/transferencias").with(webWrite()).header("Idempotency-Key", "t-2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());
        // el post de plata no se reintenta
        verify(bankClient, times(1)).transfer(any(), eq("t-2"), eq("web-token"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor webWrite() {
        return jwt().jwt(j -> j.tokenValue("web-token")).authorities(new SimpleGrantedAuthority("SCOPE_web:write"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor webToken() {
        return jwt().jwt(j -> j.tokenValue("web-token")).authorities(new SimpleGrantedAuthority("SCOPE_web:read"));
    }
}
