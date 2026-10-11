package cl.duoc.bank.shared;

import java.math.BigDecimal;
import java.util.List;

public final class DomainDtos {
    private DomainDtos() { }
    // ms-clientes
    public record Profile(String id, String name, String email, String phone, String segment) { }
    public record ProfileUpdate(String name, String email, String phone) { }
    // ms-cuentas
    public record Account(long id, String owner, String displayName, String maskedNumber,
                          String currency, BigDecimal balance, String status, String origin) { }
    public record Movement(long id, long accountId, String date, String type,
                           BigDecimal amount, String description, String source) { }
    public record MovementPage(List<Movement> items, int page, int size, long total) { }
    public record OpenAccount(String displayName, String currency) { }
    // lo que piden y responden los endpoints internos de ms-cuentas (debito, credito, reversa).
    // counterpartAccount es la otra cuenta de una transferencia (puede venir null), con eso se revisa la moneda
    public record LegRequest(String operationId, BigDecimal amount, String owner, String type, String description,
                             Long counterpartAccount) { }
    public record LegResult(String operationId, long accountId, String owner, BigDecimal amount,
                            BigDecimal balance, String currency, String status) { }
    // ms-pagos
    public record WithdrawalRequest(BigDecimal amount) { }
    public record OperationRequest(Long fromAccount, Long toAccount, String biller, BigDecimal amount) { }
    public record Receipt(String operationId, String type, Long fromAccount, Long toAccount, BigDecimal amount,
                          BigDecimal balance, String status, String detail) { }
}
