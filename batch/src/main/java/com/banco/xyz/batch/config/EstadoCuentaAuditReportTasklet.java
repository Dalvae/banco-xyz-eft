package com.banco.xyz.batch.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

public class EstadoCuentaAuditReportTasklet implements Tasklet {
    private final JdbcTemplate jdbcTemplate;
    private final String outputDirectory;
    private final String datasetId;

    public EstadoCuentaAuditReportTasklet(JdbcTemplate jdbcTemplate, String outputDirectory, String datasetId) {
        this.jdbcTemplate = jdbcTemplate;
        this.outputDirectory = outputDirectory;
        this.datasetId = datasetId;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("cuenta_id,fecha,tipo,monto,clasificacion,monto_aplicado");
        jdbcTemplate.query("SELECT cuenta_id, fecha_proceso, tipo_movimiento, total_ingresos, total_retiros, movimiento_neto_anual "
                + "FROM estado_cuenta_movimiento WHERE dataset_id = ? ORDER BY source_key", rs -> {
                    double ingresos = rs.getDouble("total_ingresos");
                    double retiros = rs.getDouble("total_retiros");
                    lines.add(csv(rs.getString("cuenta_id")) + ',' + csv(rs.getDate("fecha_proceso").toString()) + ','
                            + csv(rs.getString("tipo_movimiento")) + ',' + csv(String.valueOf(ingresos > 0 ? ingresos : retiros))
                            + ',' + csv(ingresos > 0 ? "ingreso" : "egreso") + ','
                            + csv(String.valueOf(rs.getDouble("movimiento_neto_anual"))));
                }, datasetId);
        Path directory = Path.of(outputDirectory);
        Files.createDirectories(directory);
        Path output = directory.resolve("estado-cuenta-"
                + contribution.getStepExecution().getJobExecutionId() + "-auditoria.csv");
        Files.write(output, lines, StandardCharsets.UTF_8);
        return RepeatStatus.FINISHED;
    }

    private String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
