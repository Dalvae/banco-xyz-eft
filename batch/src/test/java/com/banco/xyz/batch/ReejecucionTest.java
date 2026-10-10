package com.banco.xyz.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.banco.xyz.batch.config.ReejecucionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

// el primer chunk falla a proposito (falla-critica), el step queda FAILED y el runner tiene que relanzar
// la misma instancia del job hasta que quede COMPLETED
@SpringBootTest(properties = {"spring.batch.job.name=transaccionesJob", "batch.demo.falla-critica=true"})
@ActiveProfiles("test")
class ReejecucionTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ReejecucionRunner runner;

    @Test
    void siFallaUnStepSeRetomaLaMismaInstanciaYTermina() {
        List<String> estados = jdbc.queryForList(
                "SELECT e.status FROM batch_job_execution e JOIN batch_job_instance i ON e.job_instance_id = i.job_instance_id "
                        + "WHERE i.job_name = 'transaccionesJob' ORDER BY e.job_execution_id", String.class);
        assertThat(estados).containsExactly("FAILED", "COMPLETED");
        // una sola instancia: la segunda corrida es restart, no una corrida nueva
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM batch_job_instance WHERE job_name = 'transaccionesJob'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transacciones", Integer.class)).isPositive();
        assertThat(runner.getExitCode()).isZero();
    }
}
