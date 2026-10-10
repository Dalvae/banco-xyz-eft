package cl.duoc.bank.pagos;

// cuando ms-cuentas no contesta (caido, timeout o circuito abierto). no se sabe si el debito alcanzo a aplicarse,
// por eso la saga pide la reversa
public class CuentasNoDisponible extends RuntimeException {
    public CuentasNoDisponible(Throwable causa) {
        super("ms-cuentas no disponible: " + causa, causa);
    }
}
