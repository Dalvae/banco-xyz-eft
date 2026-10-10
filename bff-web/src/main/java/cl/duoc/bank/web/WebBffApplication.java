package cl.duoc.bank.web;

import cl.duoc.bank.shared.BankClient;
import cl.duoc.bank.shared.HttpConfig;
import cl.duoc.bank.shared.SecurityConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({SecurityConfiguration.class, BankClient.class, HttpConfig.class})
public class WebBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebBffApplication.class, args);
    }
}
