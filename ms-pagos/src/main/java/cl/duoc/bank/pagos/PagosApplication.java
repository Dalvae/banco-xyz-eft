package cl.duoc.bank.pagos;

import cl.duoc.bank.shared.ApiExceptionHandler;
import cl.duoc.bank.shared.HttpConfig;
import cl.duoc.bank.shared.SecurityConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

// microservicio de procesamiento de pagos: retiros, depositos, transferencias y pago de servicios.
// no guarda saldos, le pide a ms-cuentas que debite/acredite y publica los eventos en kafka
@SpringBootApplication
@Import({SecurityConfiguration.class, ApiExceptionHandler.class, HttpConfig.class})
@EnableScheduling
public class PagosApplication {
    public static void main(String[] args) {
        SpringApplication.run(PagosApplication.class, args);
    }
}
