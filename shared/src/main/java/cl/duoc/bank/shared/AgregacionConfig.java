package cl.duoc.bank.shared;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// pool para las llamadas en paralelo de web y movil (perfil + cuentas + movimientos a la vez).
// antes cada bff tenia su copia igualita, ahora la importan de aca. el nombre del hilo sale del servicio
// (bff-web-aggregation-1, bff-mobile-aggregation-1), asi en los logs se sigue sabiendo de quien es
@Configuration
public class AgregacionConfig {

    @Bean("aggregationExecutor")
    ThreadPoolTaskExecutor aggregationExecutor(@Value("${spring.application.name}") String servicio) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix(servicio + "-aggregation-");
        // si se llena la cola lo corre el mismo hilo del request en vez de rechazarlo
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(3);
        return executor;
    }
}
