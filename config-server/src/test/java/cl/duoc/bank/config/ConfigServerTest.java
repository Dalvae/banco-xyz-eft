package cl.duoc.bank.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

// lee el config-repo de verdad (la carpeta de al lado)
@SpringBootTest(properties = {"CONFIG_REPO=file:../config-repo", "CONFIG_PASSWORD=test"})
@AutoConfigureMockMvc
class ConfigServerTest {
    @Autowired MockMvc mvc;

    @Test
    void entregaLaConfigDeCadaServicioConClave() throws Exception {
        mvc.perform(get("/ms-pagos/default").with(httpBasic("config", "test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertySources[0].name").value("file:../config-repo/ms-pagos.yml"));
        mvc.perform(get("/ms-pagos/default")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
