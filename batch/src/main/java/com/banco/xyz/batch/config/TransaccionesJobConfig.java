package com.banco.xyz.batch.config;

import java.time.LocalDate;

import javax.sql.DataSource;

import com.banco.xyz.batch.dtos.TransaccionesDTO;
import com.banco.xyz.batch.entities.TransaccionesEntity;
import com.banco.xyz.batch.partitioner.CsvPartitioner;
import com.banco.xyz.batch.processor.TransaccionesProcessor;
import com.banco.xyz.batch.report.OmisionCollector;
import com.banco.xyz.batch.normalizer.TransaccionesNormalizer;
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
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class TransaccionesJobConfig {


    @Bean
    @StepScope
    public FlatFileItemReader<TransaccionesDTO> transaccionesItemReader(
            @Value("${ruta.archivo.transacciones}") Resource archivo,
            @Value("#{stepExecutionContext['startLine']}") int startLine,
            @Value("#{stepExecutionContext['maxItems']}") int maxItems) {
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames("id", "fecha", "monto", "tipo");
        BeanWrapperFieldSetMapper<TransaccionesDTO> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(TransaccionesDTO.class);
        DefaultConversionService conversionService = new DefaultConversionService();
        conversionService.addConverter(String.class, LocalDate.class, new FlexibleLocalDateConverter());
        mapper.setConversionService(conversionService);
        DefaultLineMapper<TransaccionesDTO> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(mapper);
        return new FlatFileItemReaderBuilder<TransaccionesDTO>().name("transaccionesItemReader")
                .resource(archivo).linesToSkip(startLine).maxItemCount(maxItems).lineMapper(lineMapper).build();
    }

    @Bean
    public TransaccionesProcessor transaccionesItemProcessor(TransaccionesNormalizer transaccionesNormalizer,
            OmisionCollector omisionCollector) {
        return new TransaccionesProcessor(transaccionesNormalizer, omisionCollector);
    }

    @Bean
    public JdbcBatchItemWriter<TransaccionesEntity> transaccionesItemWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<TransaccionesEntity>().dataSource(dataSource)
                .sql("INSERT INTO transacciones (id, fecha, monto, tipo) VALUES (:id, :fecha, :monto, :tipo) ON DUPLICATE KEY UPDATE fecha = VALUES(fecha), monto = VALUES(monto), tipo = VALUES(tipo)")
                .beanMapped().build();
    }

    @Bean
    public Step transaccionesStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            FlatFileItemReader<TransaccionesDTO> transaccionesItemReader,
            TransaccionesProcessor transaccionesItemProcessor,
            JdbcBatchItemWriter<TransaccionesEntity> transaccionesItemWriter,
            InvalidDataSkipPolicy invalidDataSkipPolicy, BatchMetricsListener batchMetricsListener,
            FallaSimulada fallaSimulada, @Value(EscaladoDefaults.CHUNK_SIZE) int chunkSize) {
        return new StepBuilder("transaccionesStep", jobRepository)
                .<TransaccionesDTO, TransaccionesEntity>chunk(chunkSize, transactionManager)
                .reader(transaccionesItemReader)
                .processor(transaccionesItemProcessor)
                .writer(transaccionesItemWriter)
                .faultTolerant()
                .skipPolicy(invalidDataSkipPolicy)
                .retry(TransientDataAccessException.class)
                .retryLimit(3)
                .backOffPolicy(new ExponentialBackOffPolicy())
                .listener((org.springframework.batch.core.SkipListener<? super TransaccionesDTO, ? super TransaccionesEntity>) batchMetricsListener)
                .listener((org.springframework.batch.core.ItemWriteListener<? super TransaccionesEntity>) batchMetricsListener)
                .listener((org.springframework.batch.core.ItemWriteListener<? super TransaccionesEntity>) fallaSimulada)
                .build();
    }

    @Bean
    public TaskExecutorPartitionHandler transaccionesPartitionHandler(Step transaccionesStep,
            @Qualifier("partitionTaskExecutor") TaskExecutor partitionTaskExecutor,
            @Value(EscaladoDefaults.GRID_SIZE) int gridSize) {

        TaskExecutorPartitionHandler partitionHandler = new TaskExecutorPartitionHandler();
        partitionHandler.setStep(transaccionesStep);
        partitionHandler.setTaskExecutor(partitionTaskExecutor);
        partitionHandler.setGridSize(gridSize);
        return partitionHandler;
    }

    @Bean
    public Step transaccionesMasterStep(JobRepository jobRepository,
            PartitionHandler transaccionesPartitionHandler,
            @Value("${ruta.archivo.transacciones}") Resource archivo) {

        CsvPartitioner partitioner = new CsvPartitioner(archivo);

        return new StepBuilder("transaccionesMasterStep", jobRepository)
                .partitioner("transaccionesStep", partitioner)
                .partitionHandler(transaccionesPartitionHandler)
                .build();
    }

    @Bean
    public Job transaccionesJob(JobRepository jobRepository, Step transaccionesMasterStep,
            BatchMetricsListener batchMetricsListener) {
        return new JobBuilder("transaccionesJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(batchMetricsListener)
                .start(transaccionesMasterStep)
                .build();
    }
}
