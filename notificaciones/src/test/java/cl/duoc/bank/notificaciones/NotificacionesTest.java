package cl.duoc.bank.notificaciones;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.duoc.bank.shared.Eventos.RetiroRealizado;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

// sin kafka de verdad: llamo el listener directo con el evento que mandaria bank-api
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notif-test",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
class NotificacionesTest {
    @Autowired MovimientosListener listener;
    @Autowired MockMvc mvc;

    @Test
    void guardaLaNotificacionDelRetiro() throws Exception {
        Acknowledgment ack = mock(Acknowledgment.class);
        listener.onMovimiento(new RetiroRealizado("op-1", 101, "demo", new BigDecimal("5000.00"),
                new BigDecimal("995000.00"), Instant.parse("2026-10-03T12:00:00Z")), ack);
        verify(ack).acknowledge();

        mvc.perform(get("/api/notificaciones").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_notificaciones:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].CUENTA_ID").value(101));
    }

    @Test
    void pausarYReanudarElListener() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("SCOPE_notificaciones:admin"));
        mvc.perform(post("/api/kafka-listener/pausar").with(admin)).andExpect(jsonPath("$.estado").value("pausado"));
        mvc.perform(post("/api/kafka-listener/reanudar").with(admin)).andExpect(jsonPath("$.estado").value("activo"));
        // con el scope de lectura no alcanza
        mvc.perform(post("/api/kafka-listener/pausar")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_notificaciones:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void endpointPideTokenYScope() throws Exception {
        mvc.perform(get("/api/notificaciones")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notificaciones").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_web:read"))))
                .andExpect(status().isForbidden());
    }
}
