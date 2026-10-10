package cl.duoc.bank.mobile.dto;

import java.math.BigDecimal;
import java.util.List;

public final class MobileDtos {
    private MobileDtos() { }

    public record Home(String name, List<AccountSummary> accounts, boolean degraded, String notice) { }
    public record AccountSummary(long id, String displayName, String maskedNumber, String currency,
                                 BigDecimal availableBalance, List<MovementSummary> movements) { }
    public record MovementSummary(long id, String date, String type, BigDecimal amount) { }
}
