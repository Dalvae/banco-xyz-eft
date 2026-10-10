package cl.duoc.bank.mobile;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.duoc.bank.shared.BankClient;
import cl.duoc.bank.shared.DomainDtos;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
class MobileControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean BankClient bankClient;

    @Test
    void inicioMuestraMaximoCincoMovimientos() throws Exception {
        when(bankClient.profile("mobile-token")).thenReturn(new DomainDtos.Profile("demo", "Demo"));
        when(bankClient.accounts("mobile-token")).thenReturn(List.of(new DomainDtos.Account(101, "demo",
                "Cuenta corriente", "****0101", "CLP", new BigDecimal("1000000"), "fixture")));
        List<DomainDtos.Movement> seis = LongStream.rangeClosed(1, 6).mapToObj(id -> new DomainDtos.Movement(id, 101,
                "2026-09-01", "compra", new BigDecimal("1000"), "detalle", "legacy")).toList();
        when(bankClient.movements(101, 0, 5, "mobile-token")).thenReturn(new DomainDtos.MovementPage(seis, 0, 5, 6));

        mvc.perform(get("/api/mobile/me/inicio").with(mobileToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Demo"))
                .andExpect(jsonPath("$.accounts[0].movements.length()").value(5));
    }

    @Test
    void siBankApiNoRespondeReintentaYDevuelveFallback() throws Exception {
        when(bankClient.profile("mobile-token")).thenThrow(new ResourceAccessException("Connection refused"));
        when(bankClient.accounts("mobile-token")).thenReturn(List.of());

        mvc.perform(get("/api/mobile/me/inicio").with(mobileToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true));
        // 3 intentos por el @Retry antes de caer al fallback
        verify(bankClient, times(3)).profile("mobile-token");
    }

    @Test
    void unErrorDeNegocioNoSeReintenta() throws Exception {
        when(bankClient.profile("mobile-token")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        when(bankClient.accounts("mobile-token")).thenReturn(List.of());

        mvc.perform(get("/api/mobile/me/inicio").with(mobileToken()))
                .andExpect(status().isForbidden());
        verify(bankClient, times(1)).profile("mobile-token");
    }

    private static RequestPostProcessor mobileToken() {
        return jwt().jwt(j -> j.tokenValue("mobile-token")).authorities(new SimpleGrantedAuthority("SCOPE_mobile:read"));
    }
}
