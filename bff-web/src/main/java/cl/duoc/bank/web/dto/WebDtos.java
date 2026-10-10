package cl.duoc.bank.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class WebDtos {
    private WebDtos() { }

    // degraded=true cuando los microservicios no responden y el circuit breaker devuelve el fallback
    public record Dashboard(Profile profile, List<Account> accounts, boolean degraded, String notice) { }
    public record Profile(String id, String name, String email, String phone, String segment) { }
    public record Account(long id, String displayName, String maskedNumber, String currency,
                          BigDecimal balance, String status, String origin, MovementPage movements) { }
    public record Movement(long id, String date, String type, BigDecimal amount,
                           String description, String source) { }
    public record MovementPage(List<Movement> items, int page, int size, long total) { }

    // lo que manda el navegador. la web es el unico canal que transfiere, paga y abre/cierra cuentas
    public record Transfer(@NotNull Long fromAccount, @NotNull Long toAccount,
                           @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount) { }
    public record BillPayment(@NotNull Long fromAccount, @NotBlank @Size(max = 60) String biller,
                              @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount) { }
    public record NewAccount(@NotBlank @Size(max = 120) String displayName, @Size(max = 3) String currency) { }
    public record ProfileChange(@Size(max = 120) String name, @Size(max = 120) String email, @Size(max = 20) String phone) { }
}
