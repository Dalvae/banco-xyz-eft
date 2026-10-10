package cl.duoc.bank.shared;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;

// la regla del monto la tenian ms-pagos y ms-cuentas copiada igual, asi queda en un solo lado
public final class Montos {

    private Montos() {
    }

    // positivo y con hasta 2 decimales (pesos con centavos). devuelve el monto con escala 2
    public static BigDecimal validar(BigDecimal monto) {
        if (monto == null || monto.signum() <= 0 || monto.scale() > 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Monto invalido: debe ser positivo y con hasta 2 decimales");
        }
        return monto.setScale(2);
    }
}
