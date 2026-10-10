package cl.duoc.bank.atm.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

// el cajero recibe y devuelve lo minimo: nada de nombres ni movimientos
public final class AtmDtos {
    private AtmDtos() { }

    public record Balance(String maskedNumber, String currency, BigDecimal availableBalance) { }
    public record Withdrawal(
            @NotNull @DecimalMin(value = "0.01") @DecimalMax(value = "200000.00")
            @Digits(integer = 12, fraction = 2) BigDecimal amount) { }
    public record Deposit(
            @NotNull @DecimalMin(value = "0.01") @DecimalMax(value = "2000000.00")
            @Digits(integer = 12, fraction = 2) BigDecimal amount) { }
    public record OperationResult(String operationId, long accountId, BigDecimal amount,
                                  BigDecimal availableBalance, String status) { }
}
