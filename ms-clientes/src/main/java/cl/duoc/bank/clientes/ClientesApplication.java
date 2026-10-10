package cl.duoc.bank.clientes;

import cl.duoc.bank.shared.ApiExceptionHandler;
import cl.duoc.bank.shared.SecurityConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

// microservicio de gestion de clientes: datos personales y perfil
@SpringBootApplication
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
public class ClientesApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClientesApplication.class, args);
    }
}
