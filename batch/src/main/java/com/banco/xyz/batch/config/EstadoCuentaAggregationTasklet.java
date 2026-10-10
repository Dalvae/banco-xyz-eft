package com.banco.xyz.batch.config;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

// Sólo el dataset anual activo.
public class EstadoCuentaAggregationTasklet implements Tasklet {
    private final JdbcTemplate jdbcTemplate;
    private final String datasetId;

    public EstadoCuentaAggregationTasklet(JdbcTemplate jdbcTemplate, String datasetId) {
        this.jdbcTemplate = jdbcTemplate;
        this.datasetId = datasetId;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        jdbcTemplate.update("DELETE FROM estado_cuenta WHERE dataset_id = ?", datasetId);
        jdbcTemplate.update("INSERT INTO estado_cuenta "
                + "(dataset_id, cuenta_id, cantidad_transacciones, total_ingresos, total_retiros, fecha_proceso, anio, movimiento_neto_anual) "
                + "SELECT dataset_id, cuenta_id, COUNT(*), SUM(total_ingresos), SUM(total_retiros), MAX(fecha_proceso), anio, SUM(movimiento_neto_anual) "
                + "FROM estado_cuenta_movimiento WHERE dataset_id = ? GROUP BY dataset_id, cuenta_id, anio", datasetId);
        return RepeatStatus.FINISHED;
    }
}
