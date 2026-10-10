package cl.duoc.bank.atm;

import cl.duoc.bank.shared.BankClient;
import cl.duoc.bank.shared.SecurityConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({SecurityConfiguration.class, BankClient.class})
public class AtmBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(AtmBffApplication.class, args);
    }
}
