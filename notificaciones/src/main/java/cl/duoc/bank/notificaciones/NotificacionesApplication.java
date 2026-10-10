package cl.duoc.bank.notificaciones;

import cl.duoc.bank.shared.SecurityConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(SecurityConfiguration.class)
public class NotificacionesApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificacionesApplication.class, args);
    }
}
