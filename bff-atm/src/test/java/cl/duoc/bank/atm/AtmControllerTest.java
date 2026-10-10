package cl.duoc.bank.atm;

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
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest
@AutoConfigureMockMvc
class AtmControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean BankClient bankClient;

    @Test
    void saldoYRetiro() throws Exception {
        when(bankClient.balance(101, "atm-token")).thenReturn(new DomainDtos.Account(101, "demo",
                "Cuenta corriente", "****0101", "CLP", new BigDecimal("1000000"), "ABIERTA", "fixture"));
        mvc.perform(get("/api/atm/cuentas/101/saldo").with(atm("SCOPE_atm:balance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(1000000));

        DomainDtos.OperationRequest pedido = new DomainDtos.OperationRequest(101L, null, null, new BigDecimal("10000"));
        when(bankClient.withdraw(pedido, "key-1", "atm-token")).thenReturn(new DomainDtos.Receipt("op-1", "RETIRO",
                101L, null, new BigDecimal("10000"), new BigDecimal("990000"), "COMPLETADA", null));
        mvc.perform(post("/api/atm/cuentas/101/retiros").with(atm("SCOPE_atm:withdraw"))
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(990000));
        verify(bankClient).withdraw(pedido, "key-1", "atm-token");

        // deposito: va como toAccount
        DomainDtos.OperationRequest deposito = new DomainDtos.OperationRequest(null, 101L, null, new BigDecimal("5000"));
        when(bankClient.deposit(deposito, "key-2", "atm-token")).thenReturn(new DomainDtos.Receipt("op-2", "DEPOSITO",
                null, 101L, new BigDecimal("5000"), null, "COMPLETADA", null));
        mvc.perform(post("/api/atm/cuentas/101/depositos").with(atm("SCOPE_atm:deposit"))
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETADA"));
    }

    @Test
    void sinMsCuentasElCajeroResponde503() throws Exception {
        when(bankClient.balance(101, "atm-token")).thenThrow(new ResourceAccessException("Connection refused"));
        mvc.perform(get("/api/atm/cuentas/101/saldo").with(atm("SCOPE_atm:balance")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void conSoloScopeDeSaldoNoPuedeRetirar() throws Exception {
        mvc.perform(post("/api/atm/cuentas/101/retiros").with(atm("SCOPE_atm:balance"))
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10000}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/atm/cuentas/101/saldo")).andExpect(status().isUnauthorized());
        verifyNoInteractions(bankClient);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor atm(String scope) {
        return jwt().jwt(j -> j.tokenValue("atm-token")).authorities(new SimpleGrantedAuthority(scope));
    }
}
