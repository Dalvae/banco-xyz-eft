package cl.duoc.bank.shared;

import java.math.BigDecimal;
import java.time.Instant;

// el evento que va por kafka, lo deje en shared para que bank-api y notificaciones usen el mismo
public final class Eventos {
    public static final String TOPICO_MOVIMIENTOS = "banco.movimientos";

    private Eventos() { }

    public record RetiroRealizado(String operationId, long cuentaId, String titular,
                                  BigDecimal monto, BigDecimal saldo, Instant fecha) { }
}
