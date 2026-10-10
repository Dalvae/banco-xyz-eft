package cl.duoc.bank.shared;

import java.math.BigDecimal;
import java.util.List;

public final class DomainDtos {
    private DomainDtos() { }
    public record Profile(String id, String name) { }
    public record Account(long id, String owner, String displayName, String maskedNumber,
                          String currency, BigDecimal balance, String origin) { }
    public record Movement(long id, long accountId, String date, String type,
                           BigDecimal amount, String description, String source) { }
    public record MovementPage(List<Movement> items, int page, int size, long total) { }
    public record WithdrawalRequest(BigDecimal amount) { }
    public record WithdrawalReceipt(String operationId, long accountId, BigDecimal amount,
                                    BigDecimal balance, String currency) { }
}
