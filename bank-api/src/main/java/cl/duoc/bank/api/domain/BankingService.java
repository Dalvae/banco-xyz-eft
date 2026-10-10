package cl.duoc.bank.api.domain;

import static cl.duoc.bank.shared.DomainDtos.*;

import cl.duoc.bank.api.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankingService {
    private static final BigDecimal MAX_WITHDRAWAL = new BigDecimal("200000.00");
    private final JdbcTemplate jdbc;
    public BankingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Profile profile(String subject) {
        return jdbc.query("SELECT id, display_name FROM customer_profile WHERE id=?", (rs, n) -> new Profile(rs.getString(1), rs.getString(2)), subject)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Cliente no encontrado"));
    }

    public List<Account> accounts(String subject) {
        return jdbc.query("SELECT id,owner,display_name,masked_number,currency,balance,origin FROM account WHERE owner=? ORDER BY id", this::account, subject);
    }

    public Account balance(long accountId, String subject) {
        Account account = accountById(accountId, false);
        requireOwner(account, subject);
        return account;
    }

    public MovementPage movements(long accountId, int page, int size, String subject, boolean mobileOnly) {
        if (page < 0 || size < 1 || size > 100 || (mobileOnly && size > 5)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Paginación fuera de rango");
        }
        requireOwner(accountById(accountId, false), subject);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM movement WHERE account_id=?", Long.class, accountId);
        List<Movement> items = jdbc.query("""
                SELECT id,account_id,movement_date,movement_type,amount,description,source
                FROM movement WHERE account_id=? ORDER BY movement_date DESC,id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> new Movement(rs.getLong(1), rs.getLong(2), rs.getDate(3).toLocalDate().toString(),
                        rs.getString(4), rs.getBigDecimal(5), rs.getString(6), rs.getString(7)), accountId, size, (long) page * size);
        return new MovementPage(items, page, size, total);
    }

    @Transactional
    public WithdrawalReceipt withdraw(long accountId, BigDecimal requestedAmount, String key, String subject) {
        BigDecimal amount = validateWithdrawal(requestedAmount);
        if (key == null || key.isBlank() || key.length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key es obligatorio y debe tener hasta 120 caracteres");
        }
        // primero bloqueo la cuenta y reviso el dueno, despues veo si este retiro ya se hizo
        Account locked = accountById(accountId, true);
        requireOwner(locked, subject);
        WithdrawalReceipt prior = previous(key, accountId, amount);
        if (prior != null) return prior;
        if (locked.balance().compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Fondos insuficientes");
        }
        BigDecimal after = locked.balance().subtract(amount);
        String operationId = UUID.randomUUID().toString();
        jdbc.update("UPDATE account SET balance=? WHERE id=?", after, accountId);
        try {
            jdbc.update("INSERT INTO withdrawal_idempotency(idempotency_key,operation_id,account_id,amount,balance_after,currency,created_at) VALUES(?,?,?,?,?,?,?)",
                    key, operationId, accountId, amount, after, locked.currency(), OffsetDateTime.now());
        } catch (DuplicateKeyException duplicateKey) {
            // la misma key desde otra cuenta, tiro error para que se haga rollback del descuento
            throw new ApiException(HttpStatus.CONFLICT, "Idempotency-Key ya utilizada");
        }
        jdbc.update("INSERT INTO movement(account_id,movement_date,movement_type,amount,description,source,operation_id) VALUES(?,CURRENT_DATE,'retiro',?,'Retiro por cajero','operacion',?)",
                accountId, amount, operationId);
        // el evento no se manda a kafka aca, se guarda en la misma transaccion (outbox).
        // asi si kafka esta caido el retiro igual queda y el evento no se pierde, lo manda MovimientosProducer
        jdbc.update("INSERT INTO evento_pendiente(operation_id,cuenta_id,titular,monto,saldo,fecha) VALUES(?,?,?,?,?,?)",
                operationId, accountId, subject, amount, after, OffsetDateTime.now());
        return new WithdrawalReceipt(operationId, accountId, amount, after, locked.currency());
    }

    private BigDecimal validateWithdrawal(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(MAX_WITHDRAWAL) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Monto inválido: debe ser positivo, máximo 200000 y hasta 2 decimales");
        }
        return amount.setScale(2);
    }

    private WithdrawalReceipt previous(String key, long accountId, BigDecimal amount) {
        return jdbc.query("SELECT operation_id,account_id,amount,balance_after,currency FROM withdrawal_idempotency WHERE idempotency_key=?", (rs, n) -> {
            if (rs.getLong(2) != accountId || rs.getBigDecimal(3).compareTo(amount) != 0) {
                throw new ApiException(HttpStatus.CONFLICT, "Idempotency-Key reutilizada con otra operación");
            }
            return new WithdrawalReceipt(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getString(5));
        }, key).stream().findFirst().orElse(null);
    }

    private Account accountById(long id, boolean lock) {
        String sql = "SELECT id,owner,display_name,masked_number,currency,balance,origin FROM account WHERE id=?" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, this::account, id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Cuenta no encontrada"));
    }

    private Account account(ResultSet rs, int row) throws SQLException {
        return new Account(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getString(7));
    }

    private void requireOwner(Account account, String subject) {
        if (!account.owner().equals(subject)) throw new ApiException(HttpStatus.FORBIDDEN, "La cuenta no pertenece al titular autenticado");
    }
}
