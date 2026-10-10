package cl.duoc.bank.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class ClientesTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void perfilDelTitularDelToken() throws Exception {
        mvc.perform(get("/clientes/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/clientes/me").with(usuario("demo", "mobile:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("demo"))
                .andExpect(jsonPath("$.segment").value("PERSONAS"));
        mvc.perform(get("/clientes/me").with(usuario("nadie", "web:read"))).andExpect(status().isNotFound());
    }

    @Test
    void actualizarPideScopeDeEscrituraYValida() throws Exception {
        mvc.perform(put("/clientes/me").with(usuario("other", "web:read"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@y.cl\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/clientes/me").with(usuario("other", "web:write"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"no-es-email\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/clientes/me").with(usuario("other", "web:write"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nuevo@bancoxyz.cl\",\"phone\":\"+56933333333\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("nuevo@bancoxyz.cl"))
                .andExpect(jsonPath("$.name").value("Otro Cliente"));
    }

    @Test
    void importaClientesLegacySinUnknown() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_import_audit", Integer.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_profile WHERE segment='LEGACY'", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_profile WHERE LOWER(display_name)='unknown'", Integer.class)).isZero();
    }

    private static RequestPostProcessor usuario(String sub, String scope) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("SCOPE_" + scope));
    }
}
