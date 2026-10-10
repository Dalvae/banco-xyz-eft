package com.banco.xyz.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class BatchApplication {

    public static void main(String[] args) {
        // Sin esto un job FAILED devuelve 0 y el orquestador lo lee como corrida exitosa.
        // el exit code ahora lo da ReejecucionRunner (0 si al final quedo COMPLETED)
        ConfigurableApplicationContext context = SpringApplication.run(BatchApplication.class, args);
        System.exit(SpringApplication.exit(context));
    }
}
