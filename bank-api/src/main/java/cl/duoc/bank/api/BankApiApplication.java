package cl.duoc.bank.api;

import cl.duoc.bank.shared.SecurityConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@Import(SecurityConfiguration.class)
@EnableScheduling
public class BankApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(BankApiApplication.class, args);
    }
}
