package cl.duoc.bank.pagos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cl.duoc.bank.pagos.eventos.EventosProducer;
import cl.duoc.bank.shared.ApiException;
import cl.duoc.bank.shared.DomainDtos.LegResult;
import cl.duoc.bank.shared.DomainDtos.Receipt;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

// sin ms-cuentas ni kafka de verdad: los dos van mockeados y reviso como queda la saga y el outbox
@SpringBootTest
@AutoConfigureMockMvc
class PagosTest {
    @Autowired MockMvc mvc;
    @Autowired PagosService pagos;
    @Autowired EventosProducer producer;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CuentasClient cuentas;
    @MockitoBean KafkaTemplate<String, String> kafka;

    // ojo: los que tiran error van con doThrow, con when(...) se llama al stub anterior con null y revienta
    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM operacion");
        jdbc.update("DELETE FROM evento_pendiente");
        reset(cuentas);
        when(cuentas.debitar(anyLong(), any())).thenAnswer(i -> pierna(i.getArgument(1, cl.duoc.bank.shared.DomainDtos.LegRequest.class).operationId(), "900000"));
        when(cuentas.acreditar(anyLong(), any())).thenAnswer(i -> pierna(i.getArgument(1, cl.duoc.bank.shared.DomainDtos.LegRequest.class).operationId(), "1000000"));
        when(cuentas.revertir(anyString())).thenAnswer(i -> reversa(i.getArgument(0)));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(kafkaOk());
    }

    @Test
    void retiroCompletoDejaEventoYEsIdempotente() {
        Receipt r = pagos.retirar("demo", 101L, new BigDecimal("20000"), "ret-1", "CAJERO");
        assertThat(r.status()).isEqualTo("COMPLETADA");
        assertThat(pendientes("banco.transacciones")).isEqualTo(1);
        assertThat(pendientes("banco.alertas-seguridad")).isZero();
        // la misma key devuelve lo mismo y no vuelve a debitar
        Receipt otra = pagos.retirar("demo", 101L, new BigDecimal("20000.00"), "ret-1", "CAJERO");
        assertThat(otra.operationId()).isEqualTo(r.operationId());
        verify(cuentas).debitar(eq(101L), any());
        assertThatThrownBy(() -> pagos.retirar("demo", 101L, new BigDecimal("1"), "ret-1", "CAJERO"))
                .isInstanceOf(ApiException.class).hasMessageContaining("reutilizada");

        producer.enviarPendientes();
        verify(kafka).send(eq("banco.transacciones"), eq("101"), anyString());
        assertThat(pendientes("banco.transacciones")).isZero();
    }

    @Test
    void montoAltoGeneraAlerta() {
        pagos.transferir("demo", 101L, 102L, new BigDecimal("150000"), "tr-alta", "WEB");
        assertThat(pendientes("banco.alertas-seguridad")).isEqualTo(1);
    }

    @Test
    void siElDestinoRechazaSeDevuelveLaPlata() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "La cuenta esta cerrada")).when(cuentas).acreditar(anyLong(), any());
        assertThatThrownBy(() -> pagos.transferir("demo", 101L, 999L, new BigDecimal("5000"), "tr-mala", "WEB"))
                .isInstanceOf(ApiException.class).hasMessageContaining("se devolvio");
        // 2 reversas: la del debito y la del credito que no llego (asi queda anulado por si llega tarde)
        verify(cuentas, times(2)).revertir(anyString());
        assertThat(estado("tr-mala")).isEqualTo("REVERTIDA");
        assertThat(pendientes("banco.transacciones")).isZero();
    }

    @Test
    void conMsCuentasCaidoSeCompensaODespuesLoArreglaElReconciliador() {
        doThrow(new CuentasNoDisponible(new RuntimeException("connection refused"))).when(cuentas).acreditar(anyLong(), any());
        doThrow(new CuentasNoDisponible(new RuntimeException("connection refused"))).when(cuentas).revertir(anyString());
        assertThatThrownBy(() -> pagos.transferir("demo", 101L, 102L, new BigDecimal("5000"), "tr-caido", "WEB"))
                .isInstanceOf(ApiException.class).hasMessageContaining("No pudimos");
        assertThat(estado("tr-caido")).isEqualTo("COMPENSACION_PENDIENTE");

        // vuelve ms-cuentas
        reset(cuentas);
        when(cuentas.revertir(anyString())).thenAnswer(i -> reversa(i.getArgument(0)));
        pagos.reconciliar();
        assertThat(estado("tr-caido")).isEqualTo("REVERTIDA");
    }

    @Test
    void laCompensacionRevierteElCreditoAntesQueElDebitoYTieneTope() {
        // el abono volvio ANULADA (no quedo aplicado): se trata como rechazo y se compensa, credito primero
        doReturn(new LegResult("x", 102, null, BigDecimal.ONE, BigDecimal.ZERO, "CLP", "ANULADA")).when(cuentas).acreditar(anyLong(), any());
        assertThatThrownBy(() -> pagos.transferir("demo", 101L, 102L, new BigDecimal("5000"), "tr-anulada", "WEB"))
                .isInstanceOf(ApiException.class).hasMessageContaining("se devolvio");
        String id = jdbc.queryForObject("SELECT operation_id FROM operacion WHERE idempotency_key='tr-anulada'", String.class);
        InOrder orden = inOrder(cuentas);
        orden.verify(cuentas).revertir(id + "-cre");
        orden.verify(cuentas).revertir(id + "-deb");
        assertThat(estado("tr-anulada")).isEqualTo("REVERTIDA");

        // si el credito no se puede revertir (el destino ya gasto la plata) no se repone el debito
        doThrow(new CuentasNoDisponible(new RuntimeException("timeout"))).when(cuentas).acreditar(anyLong(), any());
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "No hay saldo para revertir el credito")).when(cuentas).revertir(anyString());
        assertThatThrownBy(() -> pagos.transferir("demo", 101L, 102L, new BigDecimal("5000"), "tr-gastada", "WEB"))
                .isInstanceOf(ApiException.class).hasMessageContaining("incierto");
        String gastada = jdbc.queryForObject("SELECT operation_id FROM operacion WHERE idempotency_key='tr-gastada'", String.class);
        verify(cuentas, never()).revertir(gastada + "-deb");
        // el reconciliador lo intenta 5 veces y despues la deja para revision
        for (int i = 0; i < 5; i++) pagos.reconciliar();
        assertThat(estado("tr-gastada")).isEqualTo("REQUIERE_REVISION");
        pagos.reconciliar();
        verify(cuentas, times(6)).revertir(gastada + "-cre");
        verify(cuentas, never()).revertir(gastada + "-deb");
    }

    @Test
    void variosRechazosSeguidosLevantanAlerta() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Fondos insuficientes")).when(cuentas).debitar(anyLong(), any());
        for (int i = 0; i < 3; i++) {
            int n = i;
            assertThatThrownBy(() -> pagos.retirar("demo", 101L, new BigDecimal("1000"), "sin-fondos-" + n, "CAJERO"))
                    .isInstanceOf(ResponseStatusException.class);
        }
        assertThat(pendientes("banco.alertas-seguridad")).isEqualTo(1);
        verify(cuentas, never()).revertir(anyString());
    }

    @Test
    void cadaCanalSoloHaceLoQueLeToca() throws Exception {
        var cajero = jwt().jwt(j -> j.subject("demo").audience(List.of("bank-atm")))
                .authorities(new SimpleGrantedAuthority("SCOPE_atm:withdraw"));
        mvc.perform(post("/pagos/transferencias").with(cajero).header("Idempotency-Key", "x")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fromAccount\":101,\"toAccount\":102,\"amount\":1000}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/pagos/retiros").with(cajero).header("Idempotency-Key", "http-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fromAccount\":101,\"amount\":1000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETADA"));
        assertThat(jdbc.queryForObject("SELECT canal FROM operacion WHERE idempotency_key='http-1'", String.class)).isEqualTo("CAJERO");
        mvc.perform(post("/pagos/retiros").with(cajero).header("Idempotency-Key", "http-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fromAccount\":101,\"amount\":250000}"))
                .andExpect(status().isBadRequest());
    }

    private String estado(String key) {
        return jdbc.queryForObject("SELECT estado FROM operacion WHERE idempotency_key=?", String.class, key);
    }

    private int pendientes(String topico) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM evento_pendiente WHERE enviado=FALSE AND topico=?", Integer.class, topico);
    }

    private static LegResult pierna(String op, String saldo) {
        return new LegResult(op, 101, "demo", BigDecimal.ONE, new BigDecimal(saldo), "CLP", "APLICADA");
    }

    private static LegResult reversa(String op) {
        return new LegResult(op, 101, "demo", BigDecimal.ONE, BigDecimal.ZERO, "CLP", "REVERTIDA");
    }

    private static CompletableFuture<SendResult<String, String>> kafkaOk() {
        RecordMetadata meta = new RecordMetadata(new TopicPartition("banco.transacciones", 0), 0, 0, 0, 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>("banco.transacciones", "x"), meta));
    }
}
