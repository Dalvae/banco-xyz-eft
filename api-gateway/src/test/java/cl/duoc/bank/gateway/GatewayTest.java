package cl.duoc.bank.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

// cargo las rutas de verdad (config-repo/api-gateway.yml) pero sin eureka, asi lb://bff-atm no tiene instancias
// y tiene que caer al fallback del circuit breaker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=file:../config-repo/api-gateway.yml",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "server.ssl.enabled=false"})
@AutoConfigureWebTestClient
class GatewayTest {
    @Autowired WebTestClient web;

    @Test
    void sinInstanciasDelBffRespondeElFallback() {
        web.get().uri("/api/atm/cuentas/101/saldo").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.canal").isEqualTo("atm").jsonPath("$.degraded").isEqualTo(true);
    }

    @Test
    void lasRutasSalenDelConfigRepo() {
        web.get().uri("/actuator/gateway/routes").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[?(@.route_id=='bff-web')].uri").isEqualTo("lb://bff-web");
    }
}
