package cl.duoc.bank.auth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

// casi todo esto lo saque del ejemplo de spring authorization server y lo adapte al banco
@Configuration
public class AuthServerConfig {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, server -> server.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // si no hay sesion lo mando al login
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain loginChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }

    // los mismos usuarios de s5: demo tiene la cuenta 101 y other la 102
    @Bean
    UserDetailsService users(@Value("${bank.auth.demo-password}") String password) {
        return new InMemoryUserDetailsManager(
                User.withUsername("demo").password("{noop}" + password).roles("CLIENTE").build(),
                User.withUsername("other").password("{noop}" + password).roles("CLIENTE").build());
    }

    @Bean
    RegisteredClientRepository clients(@Value("${bank.auth.redirect-uri}") String redirectUri,
                                       @Value("${bank.auth.ops-secret}") String opsSecret,
                                       @Value("${bank.auth.pagos-secret}") String pagosSecret) {
        // un cliente por canal, con authorization code + PKCE porque hay un usuario que se loguea.
        // cada canal tiene solo sus scopes: la web lee y escribe, el movil solo lee y el cajero saldo/retiro/deposito
        RegisteredClient web = clienteCanal("bank-web", redirectUri, "web:read", "web:write");
        RegisteredClient mobile = clienteCanal("bank-mobile", redirectUri, "mobile:read");
        RegisteredClient atm = clienteCanal("bank-atm", redirectUri, "atm:balance", "atm:withdraw", "atm:deposit");

        // client_credentials (sin usuario), para consultar el servicio de notificaciones
        RegisteredClient ops = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("ops-notificaciones")
                .clientSecret("{noop}" + opsSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("notificaciones:read")
                .scope("notificaciones:admin") // para pausar/reanudar el consumidor kafka
                .build();

        // ms-pagos llama a los endpoints /internal de ms-cuentas con su propio token (servicio a servicio)
        RegisteredClient pagos = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("ms-pagos")
                .clientSecret("{noop}" + pagosSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("cuentas:operar")
                .build();
        return new InMemoryRegisteredClientRepository(web, mobile, atm, ops, pagos);
    }

    private static RegisteredClient clienteCanal(String clientId, String redirectUri, String... scopes) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                // cliente publico (sin secreto), por eso PKCE es obligatorio
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scopes(s -> s.addAll(List.of(scopes)))
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .build();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource() throws NoSuchAlgorithmException {
        // la llave se genera cada vez que parte, si se reinicia hay que pedir token de nuevo
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(@Value("${bank.auth.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }
}
