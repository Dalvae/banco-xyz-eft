package cl.duoc.bank.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// la puerta de entrada: https en el 8443 y de ahi enruta a los bff por eureka (lb://bff-web, etc).
// las rutas estan en config-repo/api-gateway.yml, asi se cambian sin recompilar
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
