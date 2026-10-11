package cl.duoc.bank.shared;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

// el RestClient que usan todos para llamarse entre ellos.
// con @LoadBalanced la url es el nombre en eureka (http://ms-cuentas) y el loadbalancer elige la instancia
@Configuration
public class HttpConfig {
    private static final Logger LOG = LoggerFactory.getLogger(HttpConfig.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer) {
        // el configurer de boot le pone la observacion, asi el traceId viaja en los headers
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // connect corto: si la instancia murio (ip que ya no existe) cada intento del retry se demoraba 2s y
        // los 3 de ms-pagos pasaban el read timeout del bff. adentro de la red de docker conectar es instantaneo
        factory.setConnectTimeout(500);
        factory.setReadTimeout(3000);
        return configurer.configure(RestClient.builder()).requestFactory(factory);
    }

    // los 4xx (403 cuenta ajena, 404, 409 sin fondos) se devuelven igual al que llamo, con el mensaje del otro servicio.
    // los 5xx y errores de conexion siguen como excepcion y los cuenta el circuit breaker
    public static void reenviar4xx(HttpRequest request, ClientHttpResponse response) throws IOException {
        String detalle = null;
        try {
            detalle = JSON.readTree(response.getBody()).path("detail").asText(null);
        } catch (IOException noEraJson) {
            // da lo mismo, va sin mensaje
        }
        throw new ResponseStatusException(response.getStatusCode(), detalle);
    }

    // el fallback que se repetia en los bff y en ms-cuentas: si el error es un 4xx (lo que deja reenviar4xx)
    // es respuesta de negocio y pasa tal cual; cualquier otra cosa es que el servicio no esta -> 503 con el mensaje
    public static ResponseStatusException reenviarOFallar(Throwable error, String mensaje) {
        if (error instanceof ResponseStatusException e) {
            return e;
        }
        LOG.warn("{} ({})", mensaje, error.toString());
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, mensaje);
    }
}
