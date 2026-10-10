package cl.duoc.bank.shared;

import java.math.BigDecimal;
import java.time.Instant;

// los eventos que van por kafka, en shared para que ms-pagos y notificaciones usen los mismos
public final class Eventos {
    public static final String TOPICO_TRANSACCIONES = "banco.transacciones";
    public static final String TOPICO_ALERTAS = "banco.alertas-seguridad";

    private Eventos() { }

    // tipo: RETIRO, DEPOSITO, TRANSFERENCIA o PAGO. origen/destino pueden venir null (un deposito no tiene origen)
    public record TransaccionCompletada(String operationId, String tipo, Long cuentaOrigen, Long cuentaDestino,
                                        String titular, BigDecimal monto, BigDecimal saldoOrigen, String canal,
                                        Instant fecha) { }

    // tipo: MONTO_ALTO o INTENTOS_FALLIDOS
    public record AlertaSeguridad(String alertaId, String tipo, String titular, Long cuentaId,
                                  String detalle, Instant fecha) { }
}
