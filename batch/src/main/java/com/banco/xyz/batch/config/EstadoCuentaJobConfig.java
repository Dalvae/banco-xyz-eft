package com.banco.xyz.batch.config;

import java.sql.Date;
import java.time.LocalDate;

import javax.sql.DataSource;

import com.banco.xyz.batch.dtos.EstadoCuentaDTO;
import com.banco.xyz.batch.entities.EstadoCuentaMovimientoEntity;
import com.banco.xyz.batch.partitioner.CsvPartitioner;
import com.banco.xyz.batch.processor.EstadoCuentaProcessor;
import com.banco.xyz.batch.report.OmisionCollector;
import com.banco.xyz.batch.normalizer.EstadoCuentaNormalizer;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class EstadoCuentaJobConfig {


    @Bean
    @StepScope
    public FlatFileItemReader<EstadoCuentaDTO> estadoCuentaItemReader(
            @Value("${ruta.archivo.estadoCuenta}") Resource archivo,
            @Value("#{stepExecutionContext['startLine']}") int startLine,
            @Value("#{stepExecutionContext['maxItems']}") int maxItems) {
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames("cuentaId", "fecha", "transaccion", "monto", "descripcion");
        BeanWrapperFieldSetMapper<EstadoCuentaDTO> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(EstadoCuentaDTO.class);
        DefaultConversionService conversionService = new DefaultConversionService();
        conversionService.addConverter(String.class, LocalDate.class, new FlexibleLocalDateConverter());
        mapper.setConversionService(conversionService);
        DefaultLineMapper<EstadoCuentaDTO> lineMapper = new DefaultLineMapper<>() {
            @Override
            public EstadoCuentaDTO mapLine(String line, int lineNumber) throws Exception {
                EstadoCuentaDTO item = super.mapLine(line, lineNumber);
                item.setSourceLine(lineNumber);
                return item;
            }
        };
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(mapper);
        return new FlatFileItemReaderBuilder<EstadoCuentaDTO>().name("estadoCuentaItemReader").resource(archivo)
                .linesToSkip(startLine).maxItemCount(maxItems).lineMapper(lineMapper).build();
    }

    @Bean
    public EstadoCuentaProcessor estadoCuentaItemProcessor(@Value("${batch.dataset-id}") String datasetId,
            EstadoCuentaNormalizer estadoCuentaNormalizer, OmisionCollector omisionCollector) {
        return new EstadoCuentaProcessor(datasetId, estadoCuentaNormalizer, omisionCollector);
    }

    @Bean
    public JdbcBatchItemWriter<EstadoCuentaMovimientoEntity> estadoCuentaItemWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<EstadoCuentaMovimientoEntity>().dataSource(dataSource)
                .sql("INSERT INTO estado_cuenta_movimiento "
                        + "(dataset_id, source_key, cuenta_id, fecha_proceso, anio, tipo_movimiento, total_ingresos, total_retiros, movimiento_neto_anual) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE cuenta_id = VALUES(cuenta_id), fecha_proceso = VALUES(fecha_proceso), "
                        + "anio = VALUES(anio), tipo_movimiento = VALUES(tipo_movimiento), "
                        + "total_ingresos = VALUES(total_ingresos), total_retiros = VALUES(total_retiros), "
                        + "movimiento_neto_anual = VALUES(movimiento_neto_anual)")
                .itemPreparedStatementSetter((item, statement) -> {
                    statement.setString(1, item.getDatasetId());
                    statement.setString(2, item.getSourceKey());
                    statement.setString(3, item.getCuentaId());
                    statement.setDate(4, Date.valueOf(item.getFechaProceso()));
                    statement.setInt(5, item.getAnio());
                    statement.setString(6, item.getTipoMovimiento());
                    statement.setDouble(7, item.getTotalIngresos());
                    statement.setDouble(8, item.getTotalRetiros());
                    statement.setDouble(9, item.getMovimientoNetoAnual());
                }).build();
    }

    @Bean
    public Step estadoCuentaStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            FlatFileItemReader<EstadoCuentaDTO> estadoCuentaItemReader, EstadoCuentaProcessor estadoCuentaItemProcessor,
            JdbcBatchItemWriter<EstadoCuentaMovimientoEntity> estadoCuentaItemWriter, InvalidDataSkipPolicy invalidDataSkipPolicy,
            BatchMetricsListener batchMetricsListener, @Value(EscaladoDefaults.CHUNK_SIZE) int chunkSize) {
        return new StepBuilder("estadoCuentaStep", jobRepository)
                .<EstadoCuentaDTO, EstadoCuentaMovimientoEntity>chunk(chunkSize, transactionManager).reader(estadoCuentaItemReader)
                .processor(estadoCuentaItemProcessor).writer(estadoCuentaItemWriter).faultTolerant()
                .skipPolicy(invalidDataSkipPolicy).retry(TransientDataAccessException.class).retryLimit(3)
                .backOffPolicy(new ExponentialBackOffPolicy())
                .listener((org.springframework.batch.core.SkipListener<? super EstadoCuentaDTO, ? super EstadoCuentaMovimientoEntity>) batchMetricsListener)
                .build();
    }

    @Bean
    public TaskExecutorPartitionHandler estadoCuentaPartitionHandler(Step estadoCuentaStep,
            @Qualifier("partitionTaskExecutor") TaskExecutor partitionTaskExecutor,
            @Value(EscaladoDefaults.GRID_SIZE) int gridSize) {
        TaskExecutorPartitionHandler partitionHandler = new TaskExecutorPartitionHandler();
        partitionHandler.setStep(estadoCuentaStep);
        partitionHandler.setTaskExecutor(partitionTaskExecutor);
        partitionHandler.setGridSize(gridSize);
        return partitionHandler;
    }

    @Bean
    public Step estadoCuentaMasterStep(JobRepository jobRepository, PartitionHandler estadoCuentaPartitionHandler,
            @Value("${ruta.archivo.estadoCuenta}") Resource archivo) {
        return new StepBuilder("estadoCuentaMasterStep", jobRepository).partitioner("estadoCuentaStep", new CsvPartitioner(archivo))
                .partitionHandler(estadoCuentaPartitionHandler).build();
    }

    @Bean
    public Tasklet estadoCuentaAggregationTasklet(JdbcTemplate jdbcTemplate,
            @Value("${batch.dataset-id}") String datasetId) {
        return new EstadoCuentaAggregationTasklet(jdbcTemplate, datasetId);
    }

    @Bean
    public Step estadoCuentaAggregationStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            @Qualifier("estadoCuentaAggregationTasklet") Tasklet estadoCuentaAggregationTasklet) {
        return new StepBuilder("estadoCuentaAggregationStep", jobRepository).tasklet(estadoCuentaAggregationTasklet,
                transactionManager).build();
    }

    @Bean
    public Tasklet estadoCuentaAuditReportTasklet(JdbcTemplate jdbcTemplate,
            @Value("${batch.reports.output-directory:${java.io.tmpdir}/banco-xyz-batch-reportes}") String outputDirectory,
            @Value("${batch.dataset-id}") String datasetId) {
        return new EstadoCuentaAuditReportTasklet(jdbcTemplate, outputDirectory, datasetId);
    }

    @Bean
    public Step estadoCuentaAuditReportStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            @Qualifier("estadoCuentaAuditReportTasklet") Tasklet estadoCuentaAuditReportTasklet) {
        return new StepBuilder("estadoCuentaAuditReportStep", jobRepository).tasklet(estadoCuentaAuditReportTasklet,
                transactionManager).build();
    }

    @Bean
    public Job estadoCuentaJob(JobRepository jobRepository, Step estadoCuentaMasterStep, Step estadoCuentaAggregationStep,
            Step estadoCuentaAuditReportStep,
            BatchMetricsListener batchMetricsListener) {
        return new JobBuilder("estadoCuentaJob", jobRepository).incrementer(new RunIdIncrementer())
                .listener(batchMetricsListener).start(estadoCuentaMasterStep).next(estadoCuentaAggregationStep)
                .next(estadoCuentaAuditReportStep).build();
    }
}
