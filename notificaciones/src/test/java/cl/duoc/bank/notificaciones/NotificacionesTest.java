package cl.duoc.bank.notificaciones;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.duoc.bank.shared.Eventos.AlertaSeguridad;
import cl.duoc.bank.shared.Eventos.TransaccionCompletada;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

// sin kafka de verdad: llamo los listeners directo con los eventos que mandaria ms-pagos
@SpringBootTest
@AutoConfigureMockMvc
class NotificacionesTest {
    @Autowired EventosListener listener;
    @Autowired MockMvc mvc;

    @Test
    void guardaTransaccionesYAlertasSinRepetir() throws Exception {
        Acknowledgment ack = mock(Acknowledgment.class);
        TransaccionCompletada retiro = new TransaccionCompletada("op-1", "RETIRO", 101L, null, "demo",
                new BigDecimal("5000.00"), new BigDecimal("995000.00"), "CAJERO", Instant.parse("2026-10-10T12:00:00Z"));
        listener.onTransaccion(retiro, ack);
        listener.onTransaccion(retiro, ack); // repetido
        listener.onAlerta(new AlertaSeguridad("al-1", "MONTO_ALTO", "demo", 101L, "RETIRO por $180000", Instant.now()), ack);

        var ops = jwt().authorities(new SimpleGrantedAuthority("SCOPE_notificaciones:read"));
        mvc.perform(get("/api/notificaciones").with(ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/notificaciones").param("topico", "banco.alertas-seguridad").with(ops))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tipo").value("MONTO_ALTO"));
        verify(ack, org.mockito.Mockito.times(3)).acknowledge();
    }

    @Test
    void pausarYReanudarElListener() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("SCOPE_notificaciones:admin"));
        mvc.perform(post("/api/notificaciones/listeners/alertasListener/pausar").with(admin)).andExpect(jsonPath("$.estado").value("pausado"));
        mvc.perform(post("/api/notificaciones/listeners/alertasListener/reanudar").with(admin)).andExpect(jsonPath("$.estado").value("activo"));
        // con el scope de lectura no alcanza
        mvc.perform(post("/api/notificaciones/listeners/alertasListener/pausar")
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
