package cl.duoc.bank.cuentas;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cl.duoc.bank.cuentas.domain.ClientesClient;
import cl.duoc.bank.cuentas.domain.CuentasService;
import cl.duoc.bank.cuentas.importer.LegacyCsvImporter;
import cl.duoc.bank.shared.ApiException;
import cl.duoc.bank.shared.DomainDtos.LegRequest;
import cl.duoc.bank.shared.DomainDtos.LegResult;
import cl.duoc.bank.shared.DomainDtos.OpenAccount;
import cl.duoc.bank.shared.DomainDtos.Profile;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class CuentasIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CuentasService cuentas;
    @Autowired LegacyCsvImporter importer;
    @MockitoBean ClientesClient clientes;

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM movement WHERE source='operacion'");
        jdbc.update("DELETE FROM operacion_aplicada");
        jdbc.update("DELETE FROM account WHERE id NOT IN (101,102)");
        jdbc.update("UPDATE account SET balance=1000000.00, status='ABIERTA' WHERE id IN (101,102)");
    }

    @Test
    void importaElLegacyUnaSolaVez() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_import_audit", Integer.class)).isEqualTo(1000);
        int antes = jdbc.queryForObject("SELECT COUNT(*) FROM movement WHERE source='legacy/cuentas_anuales.csv'", Integer.class);
        assertThat(antes).isPositive()
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_import_audit WHERE status='IMPORTED'", Integer.class));
        importer.run(null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM movement WHERE source='legacy/cuentas_anuales.csv'", Integer.class)).isEqualTo(antes);
    }

    @Test
    void scopesYDuenoDeLaCuenta() throws Exception {
        mvc.perform(get("/cuentas/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/cuentas/101/saldo").with(usuario("demo", "atm:balance")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.owner").value("demo"));
        mvc.perform(get("/cuentas/102/saldo").with(usuario("demo", "atm:balance")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/cuentas/me").with(usuario("demo", "web:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(101));
        // un usuario no puede llamar los endpoints internos aunque tenga scopes de escritura
        mvc.perform(post("/internal/cuentas/101/creditos").with(usuario("demo", "web:write"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operationId\":\"x\",\"amount\":1000}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/cuentas/101/debitos").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_cuentas:operar")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operationId\":\"op-http\",\"amount\":1000,\"owner\":\"demo\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(999000));
    }

    @Test
    void debitoIdempotenteYSinFondos() {
        LegResult primero = cuentas.debitar(101, pierna("op-1", "12500", "demo"));
        LegResult repetido = cuentas.debitar(101, pierna("op-1", "12500.00", "demo"));
        assertThat(repetido.balance()).isEqualByComparingTo(primero.balance());
        assertThat(cuentas.balance(101, "demo").balance()).isEqualByComparingTo("987500.00");
        assertThatThrownBy(() -> cuentas.debitar(101, pierna("op-1", "13000", "demo")))
                .isInstanceOf(ApiException.class).hasMessageContaining("reutilizado");
        assertThatThrownBy(() -> cuentas.debitar(101, pierna("op-2", "5000000", "demo")))
                .isInstanceOf(ApiException.class).hasMessageContaining("Fondos");
        // el debito con titular distinto no pasa
        assertThatThrownBy(() -> cuentas.debitar(102, pierna("op-3", "100", "demo")))
                .isInstanceOf(ApiException.class).hasMessageContaining("no pertenece");
        assertThat(cuentas.balance(101, "demo").balance()).isEqualByComparingTo("987500.00");
    }

    @Test
    void transferenciaEntreMonedasDistintasSeRechazaSinMoverPlata() {
        long usd = cuentas.abrir("other", new OpenAccount("Cuenta dolares", "USD")).id();
        LegRequest aDolares = new LegRequest("tr-usd-deb", new BigDecimal("1000"), "demo", "transferencia", null, usd);
        assertThatThrownBy(() -> cuentas.debitar(101, aDolares))
                .isInstanceOf(ApiException.class).hasMessageContaining("distinta moneda");
        assertThat(cuentas.balance(101, "demo").balance()).isEqualByComparingTo("1000000.00");
        // entre 2 cuentas CLP pasa normal
        cuentas.debitar(101, new LegRequest("tr-clp-deb", new BigDecimal("1000"), "demo", "transferencia", null, 102L));
        assertThat(cuentas.balance(101, "demo").balance()).isEqualByComparingTo("999000.00");
    }

    @Test
    void reversaDevuelveLaPlataYAnulaLoQueNoLlego() {
        cuentas.debitar(101, pierna("tr-1-deb", "30000", "demo"));
        cuentas.revertir("tr-1-deb");
        assertThat(cuentas.balance(101, "demo").balance()).isEqualByComparingTo("1000000.00");
        // revertir 2 veces no devuelve 2 veces
        cuentas.revertir("tr-1-deb");
        assertThat(cuentas.balance(101, "demo").balance()).isEqualByComparingTo("1000000.00");

        // reversa de algo que nunca llego: queda anulado y si llega tarde se rechaza
        assertThat(cuentas.revertir("tr-2-cre").status()).isEqualTo("ANULADA");
        assertThatThrownBy(() -> cuentas.acreditar(102, pierna("tr-2-cre", "30000", null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("anulada");
        assertThat(cuentas.balance(102, "other").balance()).isEqualByComparingTo("1000000.00");
    }

    @Test
    void abrirRenombrarYCerrarCuenta() throws Exception {
        when(clientes.perfil(anyString())).thenReturn(new Profile("demo", "Demo", null, null, null));
        String body = mvc.perform(post("/cuentas").with(usuario("demo", "web:write"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Ahorro viaje\",\"currency\":\"CLP\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ABIERTA"))
                .andExpect(jsonPath("$.balance").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(patch("/cuentas/" + id).with(usuario("demo", "web:write"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Ahorro Japon\"}"))
                .andExpect(jsonPath("$.displayName").value("Ahorro Japon"));
        // con saldo no se puede cerrar
        cuentas.acreditar(id, pierna("dep-1", "1000", null));
        mvc.perform(post("/cuentas/" + id + "/cierre").with(usuario("demo", "web:write"))).andExpect(status().isConflict());
        cuentas.debitar(id, pierna("ret-1", "1000", "demo"));
        mvc.perform(post("/cuentas/" + id + "/cierre").with(usuario("demo", "web:write")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CERRADA"));
        // a una cuenta cerrada no le entra plata
        assertThatThrownBy(() -> cuentas.acreditar(id, pierna("dep-2", "1000", null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("cerrada");
        // con el token de lectura no se abren cuentas
        mvc.perform(post("/cuentas").with(usuario("demo", "web:read"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    private static LegRequest pierna(String op, String monto, String owner) {
        return new LegRequest(op, new BigDecimal(monto), owner, null, null, null);
    }

    private static RequestPostProcessor usuario(String sub, String scope) {
        return jwt().jwt(j -> j.subject(sub).claim("scope", List.of(scope)))
                .authorities(new SimpleGrantedAuthority("SCOPE_" + scope));
    }
}
