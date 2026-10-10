package cl.duoc.bank.shared;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

// lo usaban web y movil igualito, asi que lo deje aca
public final class Llamadas {

    private Llamadas() {
    }

    // espera todas las llamadas en paralelo y si una falla tira el error original (no el CompletionException)
    // asi el retry y el circuit breaker ven la excepcion de verdad
    public static void esperarTodas(CompletableFuture<?>... calls) {
        try {
            CompletableFuture.allOf(calls).join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }
}
