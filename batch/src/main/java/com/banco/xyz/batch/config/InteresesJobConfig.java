package com.banco.xyz.batch.config;

import javax.sql.DataSource;

import com.banco.xyz.batch.dtos.InteresesDTO;
import com.banco.xyz.batch.entities.InteresesEntity;
import com.banco.xyz.batch.partitioner.CsvPartitioner;
import com.banco.xyz.batch.processor.InteresesProcessor;
import com.banco.xyz.batch.report.OmisionCollector;
import com.banco.xyz.batch.normalizer.InteresesNormalizer;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
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
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class InteresesJobConfig {


    @Bean
    @StepScope
    public FlatFileItemReader<InteresesDTO> interesesItemReader(
            @Value("${ruta.archivo.intereses}") Resource archivo,
            @Value("#{stepExecutionContext['startLine']}") int startLine,
            @Value("#{stepExecutionContext['maxItems']}") int maxItems) {
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames("cuentaId", "nombre", "saldo", "edad", "tipo");
        BeanWrapperFieldSetMapper<InteresesDTO> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(InteresesDTO.class);
        DefaultLineMapper<InteresesDTO> lineMapper = new DefaultLineMapper<>() {
            @Override
            public InteresesDTO mapLine(String line, int lineNumber) throws Exception {
                InteresesDTO item = super.mapLine(line, lineNumber);
                item.setSourceLine(lineNumber);
                return item;
            }
        };
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(mapper);
        return new FlatFileItemReaderBuilder<InteresesDTO>()
                .name("interesesItemReader")
                .resource(archivo)
                .linesToSkip(startLine)
                .maxItemCount(maxItems)
                .lineMapper(lineMapper)
                .build();
    }

    @Bean
    public InteresesProcessor interesesItemProcessor(@Value("${batch.intereses.dataset-id}") String datasetId,
            InteresesNormalizer interesesNormalizer, OmisionCollector omisionCollector) {
        return new InteresesProcessor(datasetId, interesesNormalizer, omisionCollector);
    }

    @Bean
    public JdbcBatchItemWriter<InteresesEntity> interesesItemWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<InteresesEntity>().dataSource(dataSource)
                .sql("INSERT INTO intereses (dataset_id, source_line, cuenta_id, nombre, saldo_base, tasa, saldo_con_interes, edad, tipo) "
                        + "VALUES (:datasetId, :sourceLine, :cuentaId, :nombre, :saldoBase, :tasa, :saldoConInteres, :edad, :tipo) "
                        + "ON DUPLICATE KEY UPDATE cuenta_id = VALUES(cuenta_id), nombre = VALUES(nombre), "
                        + "saldo_base = VALUES(saldo_base), tasa = VALUES(tasa), saldo_con_interes = VALUES(saldo_con_interes), "
                        + "edad = VALUES(edad), tipo = VALUES(tipo)")
                .beanMapped().build();
    }

    @Bean
    public Step interesesStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            FlatFileItemReader<InteresesDTO> interesesItemReader, InteresesProcessor interesesItemProcessor,
            JdbcBatchItemWriter<InteresesEntity> interesesItemWriter,
            InvalidDataSkipPolicy invalidDataSkipPolicy, BatchMetricsListener batchMetricsListener,
            @Value(EscaladoDefaults.CHUNK_SIZE) int chunkSize) {
        return new StepBuilder("interesesStep", jobRepository)
                .<InteresesDTO, InteresesEntity>chunk(chunkSize, transactionManager)
                .reader(interesesItemReader)
                .processor(interesesItemProcessor)
                .writer(interesesItemWriter)
                .faultTolerant()
                .skipPolicy(invalidDataSkipPolicy)
                .retry(TransientDataAccessException.class)
                .retryLimit(3)
                .backOffPolicy(new ExponentialBackOffPolicy())
                .listener((org.springframework.batch.core.SkipListener<? super InteresesDTO, ? super InteresesEntity>) batchMetricsListener)
                .build();
    }

    @Bean
    public TaskExecutorPartitionHandler interesesPartitionHandler(Step interesesStep,
            @Qualifier("partitionTaskExecutor") TaskExecutor partitionTaskExecutor,
            @Value(EscaladoDefaults.GRID_SIZE) int gridSize) {

        TaskExecutorPartitionHandler partitionHandler = new TaskExecutorPartitionHandler();
        partitionHandler.setStep(interesesStep);
        partitionHandler.setTaskExecutor(partitionTaskExecutor);
        partitionHandler.setGridSize(gridSize);
        return partitionHandler;
    }

    @Bean
    public Step interesesMasterStep(JobRepository jobRepository,
            PartitionHandler interesesPartitionHandler,
            @Value("${ruta.archivo.intereses}") Resource archivo) {

        CsvPartitioner partitioner = new CsvPartitioner(archivo);

        return new StepBuilder("interesesMasterStep", jobRepository)
                .partitioner("interesesStep", partitioner)
                .partitionHandler(interesesPartitionHandler)
                .build();
    }

    @Bean
    public Job interesesJob(JobRepository jobRepository, Step interesesMasterStep,
            BatchMetricsListener batchMetricsListener) {
        return new JobBuilder("interesesJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(batchMetricsListener)
                .start(interesesMasterStep)
                .build();
    }
}
