package cl.duoc.bank.web.dto;

import java.math.BigDecimal;
import java.util.List;

public final class WebDtos {
    private WebDtos() { }

    // degraded=true cuando bank-api no responde y el circuit breaker devuelve el fallback
    public record Dashboard(Profile profile, List<Account> accounts, boolean degraded, String notice) { }
    public record Profile(String id, String name) { }
    public record Account(long id, String displayName, String maskedNumber, String currency,
                          BigDecimal balance, String origin, MovementPage movements) { }
    public record Movement(long id, String date, String type, BigDecimal amount,
                           String description, String source) { }
    public record MovementPage(List<Movement> items, int page, int size, long total) { }
}
