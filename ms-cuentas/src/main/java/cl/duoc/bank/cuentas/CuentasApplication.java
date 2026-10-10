package cl.duoc.bank.cuentas;

import cl.duoc.bank.shared.ApiExceptionHandler;
import cl.duoc.bank.shared.HttpConfig;
import cl.duoc.bank.shared.SecurityConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

// microservicio de gestion de cuentas: apertura, cierre, mantenimiento, saldo y movimientos.
// es el unico que toca el saldo, ms-pagos le pide debitos y creditos por /internal
@SpringBootApplication
@Import({SecurityConfiguration.class, ApiExceptionHandler.class, HttpConfig.class})
public class CuentasApplication {
    public static void main(String[] args) {
        SpringApplication.run(CuentasApplication.class, args);
    }
}
