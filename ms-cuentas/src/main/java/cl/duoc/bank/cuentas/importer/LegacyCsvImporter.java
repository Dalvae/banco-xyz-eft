package cl.duoc.bank.cuentas.importer;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class LegacyCsvImporter implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyCsvImporter.class);
    static final String SOURCE = "legacy/cuentas_anuales.csv";
    private static final Set<String> TYPES = Set.of("deposito", "compra", "retiro", "pago");
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(java.time.format.ResolverStyle.STRICT));
    private final JdbcTemplate jdbc;

    public LegacyCsvImporter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        ClassPathResource resource = new ClassPathResource(SOURCE);
        if (!resource.exists()) return;
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        try (var reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);
             var parser = format.parse(reader)) {
            for (CSVRecord record : parser) importRecord(record);
        }
        int imported = jdbc.queryForObject("SELECT COUNT(*) FROM legacy_import_audit WHERE source=? AND status='IMPORTED'", Integer.class, SOURCE);
        int discarded = jdbc.queryForObject("SELECT COUNT(*) FROM legacy_import_audit WHERE source=? AND status='DISCARDED'", Integer.class, SOURCE);
        LOG.info("Importacion legacy auditada: source={}, imported={}, discarded={}", SOURCE, imported, discarded);
        jdbc.queryForList("SELECT reason,COUNT(*) AS total FROM legacy_import_audit WHERE source=? AND status='DISCARDED' GROUP BY reason ORDER BY reason", SOURCE)
                .forEach(row -> LOG.info("Descarte legacy: reason={}, count={}", row.get("reason"), row.get("total")));
    }

    private void importRecord(CSVRecord record) {
        // guardo la linea de origen para no meter el mismo movimiento otra vez al reiniciar.
        // con insert ignore si parten 2 replicas a la vez solo una gana la linea y la otra la salta
        int line = Math.toIntExact(record.getRecordNumber() + 1);
        String rawAccount = record.get("cuenta_id").trim();
        Result result = validate(record, rawAccount);
        boolean importada = result.reason() == null;
        int nueva = jdbc.update("INSERT IGNORE INTO legacy_import_audit(source,source_line,status,reason,raw_account) VALUES(?,?,?,?,?)",
                SOURCE, line, importada ? "IMPORTED" : "DISCARDED", result.reason(), rawAccount);
        if (nueva == 0 || !importada) return;
        jdbc.update("INSERT INTO movement(account_id,movement_date,movement_type,amount,description,source,source_line) VALUES(?,?,?,?,?,?,?)",
                result.accountId(), Date.valueOf(result.date()), result.type(), result.amount(), record.get("descripcion").trim(), SOURCE, line);
    }

    private Result validate(CSVRecord record, String rawAccount) {
        long accountId;
        try { accountId = Long.parseLong(rawAccount); }
        catch (NumberFormatException invalid) { return Result.discard("INVALID_ACCOUNT"); }
        if (accountId != 101 && accountId != 102) return Result.discard("NON_OPERATIONAL_ACCOUNT");

        LocalDate date = parseDate(record.get("fecha").trim());
        if (date == null) return Result.discard("INVALID_DATE");
        String type = normalize(record.get("transaccion"));
        if (!TYPES.contains(type)) return Result.discard("INVALID_TYPE");
        BigDecimal amount;
        try { amount = new BigDecimal(record.get("monto").trim()); }
        catch (NumberFormatException invalid) { return Result.discard("INVALID_AMOUNT"); }
        if (amount.signum() <= 0 || amount.scale() > 2) return Result.discard("INVALID_AMOUNT");
        return new Result(accountId, date, type, amount.setScale(2), null);
    }

    private static LocalDate parseDate(String raw) {
        for (DateTimeFormatter formatter : DATES) {
            try { return LocalDate.parse(raw, formatter); }
            catch (DateTimeParseException ignored) { }
        }
        return null;
    }

    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private record Result(long accountId, LocalDate date, String type, BigDecimal amount, String reason) {
        static Result discard(String reason) { return new Result(0, null, null, null, reason); }
    }
}
