package com.banco.xyz.batch.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import com.banco.xyz.batch.normalizer.EstadoCuentaNormalizer;
import com.banco.xyz.batch.normalizer.InteresesNormalizer;
import com.banco.xyz.batch.normalizer.TransaccionesNormalizer;

@Configuration
public class BatchInfrastructureConfig {

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    public TaskExecutor partitionTaskExecutor(@Value(EscaladoDefaults.POOL_SIZE) int poolSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("batch-");
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }

    @Bean
    public InteresesNormalizer interesesNormalizer() {
        return new InteresesNormalizer();
    }

    @Bean
    public EstadoCuentaNormalizer estadoCuentaNormalizer() {
        return new EstadoCuentaNormalizer();
    }

    @Bean
    public TransaccionesNormalizer transaccionesNormalizer() {
        return new TransaccionesNormalizer();
    }
}
