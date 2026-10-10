package cl.duoc.bank.atm.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public final class AtmDtos {
    private AtmDtos() { }

    public record Balance(String maskedNumber, String currency, BigDecimal availableBalance) { }
    public record Withdrawal(
            @NotNull @DecimalMin(value = "0.01") @DecimalMax(value = "200000.00")
            @Digits(integer = 12, fraction = 2) BigDecimal amount) { }
    public record WithdrawalResult(String operationId, long accountId, BigDecimal amount,
                                   BigDecimal availableBalance, String currency) { }
}
