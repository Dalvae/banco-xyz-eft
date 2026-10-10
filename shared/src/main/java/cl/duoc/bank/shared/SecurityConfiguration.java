package cl.duoc.bank.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// la misma config para todos los servicios: son resource servers y validan el jwt del auth-server
// (el issuer, jwks y audiencia van en el application.properties de cada uno)
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // sin cookies ni sesion, solo bearer token, asi que csrf no aplica
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // health, el estado del circuito y los reintentos abiertos para la demo (no tienen datos de clientes)
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/circuitbreakers", "/actuator/circuitbreakerevents",
                                "/actuator/retries", "/actuator/retryevents/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
