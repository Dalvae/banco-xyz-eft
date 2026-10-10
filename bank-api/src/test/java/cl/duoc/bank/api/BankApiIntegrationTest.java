package cl.duoc.bank.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cl.duoc.bank.api.domain.BankingService;
import cl.duoc.bank.api.eventos.MovimientosProducer;
import cl.duoc.bank.api.web.ApiException;
import cl.duoc.bank.api.importer.LegacyCsvImporter;
import cl.duoc.bank.shared.DomainDtos.WithdrawalReceipt;
import cl.duoc.bank.shared.Eventos.RetiroRealizado;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.support.SendResult;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@SpringBootTest
@AutoConfigureMockMvc
class BankApiIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:bank-api-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        // en los tests el envio lo llamo a mano, no con el @Scheduled
        registry.add("eventos.reintento-ms", () -> "3600000");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BankingService banking;
    @Autowired LegacyCsvImporter importer;
    @Autowired DataSource dataSource;
    @Autowired MovimientosProducer producer;
    @MockitoBean KafkaTemplate<String, RetiroRealizado> kafka;

    @BeforeEach
    void resetOperationalState() {
        when(kafka.send(anyString(), anyString(), any(RetiroRealizado.class))).thenReturn(kafkaOk());
        jdbc.update("DELETE FROM evento_pendiente");
        jdbc.update("DELETE FROM movement WHERE source='operacion'");
        jdbc.update("DELETE FROM withdrawal_idempotency");
        jdbc.update("UPDATE account SET balance=1000000.00 WHERE id IN (101,102)");
    }

    @Test
    void importsLegacyOnceAndAuditsEveryDataRow() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_import_audit", Integer.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_import_audit WHERE status='IMPORTED'", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_import_audit WHERE status='DISCARDED'", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM movement WHERE source='legacy/cuentas_anuales.csv'", Integer.class))
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_import_audit WHERE status='IMPORTED'", Integer.class));
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM movement WHERE source='legacy/cuentas_anuales.csv'", Integer.class);
        importer.run(null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM movement WHERE source='legacy/cuentas_anuales.csv'", Integer.class)).isEqualTo(before);
        jdbc.update("UPDATE account SET balance=876543.21 WHERE id=101");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        assertThat(banking.balance(101, "demo").balance()).isEqualByComparingTo("876543.21");
    }

    @Test
    void enforcesAuthenticationScopeAndOwnership() throws Exception {
        mvc.perform(get("/internal/me/accounts")).andExpect(status().isUnauthorized());
        var atmRead = jwt().jwt(j -> j.subject("demo").claim("scope", List.of("atm:balance")))
                .authorities(new SimpleGrantedAuthority("SCOPE_atm:balance"));
        mvc.perform(get("/internal/accounts/101/balance").with(atmRead))
                .andExpect(status().isOk()).andExpect(jsonPath("$.owner").value("demo"));
        mvc.perform(get("/internal/accounts/102/balance").with(atmRead))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/accounts/101/withdrawals").with(atmRead)
                        .header("Idempotency-Key", "read-token").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
                .andExpect(status().isForbidden());
        var web = jwt().jwt(j -> j.subject("demo").claim("scope", List.of("web:read")))
                .authorities(new SimpleGrantedAuthority("SCOPE_web:read"));
        mvc.perform(get("/internal/me/accounts").with(web)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(101));
        mvc.perform(post("/internal/accounts/101/withdrawals").with(web)
                        .header("Idempotency-Key", "web-token").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void retiroGuardaEventoYDespuesLoPublica() throws Exception {
        var atm = jwt().jwt(j -> j.subject("demo").claim("scope", List.of("atm:balance", "atm:withdraw")))
                .authorities(new SimpleGrantedAuthority("SCOPE_atm:withdraw"));
        mvc.perform(post("/internal/accounts/101/withdrawals").with(atm)
                        .header("Idempotency-Key", "evento-1").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5000}"))
                .andExpect(status().isOk());
        // el retiro solo deja el evento en la tabla, todavia no llama a kafka
        verifyNoInteractions(kafka);
        assertThat(pendientes()).isEqualTo(1);

        producer.enviarPendientes();
        ArgumentCaptor<RetiroRealizado> payload = ArgumentCaptor.forClass(RetiroRealizado.class);
        verify(kafka).send(eq("banco.movimientos"), eq("101"), payload.capture());
        assertThat(payload.getValue().cuentaId()).isEqualTo(101);
        assertThat(payload.getValue().monto()).isEqualByComparingTo("5000");
        assertThat(pendientes()).isZero();

        // si el retiro falla (sin fondos) no queda ningun evento
        clearInvocations(kafka);
        jdbc.update("UPDATE account SET balance=10.00 WHERE id=101");
        mvc.perform(post("/internal/accounts/101/withdrawals").with(atm)
                        .header("Idempotency-Key", "evento-2").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5000}"))
                .andExpect(status().isConflict());
        producer.enviarPendientes();
        verifyNoInteractions(kafka);
    }

    @Test
    void conKafkaCaidoElEventoNoSePierde() {
        when(kafka.send(anyString(), anyString(), any(RetiroRealizado.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("kafka caido")));
        banking.withdraw(101, new BigDecimal("3000"), "kafka-caido", "demo");
        producer.enviarPendientes();
        // el retiro se hizo y el evento sigue esperando
        assertThat(banking.balance(101, "demo").balance()).isEqualByComparingTo("997000.00");
        assertThat(pendientes()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT intentos FROM evento_pendiente", Integer.class)).isEqualTo(1);

        // vuelve kafka y en la siguiente vuelta se manda
        when(kafka.send(anyString(), anyString(), any(RetiroRealizado.class))).thenReturn(kafkaOk());
        producer.enviarPendientes();
        assertThat(pendientes()).isZero();
    }

    private int pendientes() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM evento_pendiente WHERE enviado=FALSE", Integer.class);
    }

    private static CompletableFuture<SendResult<String, RetiroRealizado>> kafkaOk() {
        RecordMetadata meta = new RecordMetadata(new TopicPartition("banco.movimientos", 0), 0, 0, 0, 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>("banco.movimientos", null), meta));
    }

    @Test
    void withdrawalIsAtomicIdempotentAndRejectsChangedBody() {
        WithdrawalReceipt first = banking.withdraw(101, new BigDecimal("12500"), "same-key", "demo");
        WithdrawalReceipt repeated = banking.withdraw(101, new BigDecimal("12500.00"), "same-key", "demo");
        assertThat(repeated).isEqualTo(first);
        assertThat(banking.balance(101, "demo").balance()).isEqualByComparingTo("987500.00");
        assertThatThrownBy(() -> banking.withdraw(101, new BigDecimal("13000"), "same-key", "demo"))
                .isInstanceOf(ApiException.class).hasMessageContaining("reutilizada");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM movement WHERE operation_id=?", Integer.class, first.operationId())).isOne();
    }

    @Test
    void insufficientFundsAndInvalidAmountsDoNotChangeBalance() {
        jdbc.update("UPDATE account SET balance=1000.00 WHERE id=101");
        assertThatThrownBy(() -> banking.withdraw(101, new BigDecimal("1500"), "too-much", "demo"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Fondos");
        assertThatThrownBy(() -> banking.withdraw(101, new BigDecimal("1.001"), "scale", "demo"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Monto inválido");
        assertThat(banking.balance(101, "demo").balance()).isEqualByComparingTo("1000.00");
    }
}
