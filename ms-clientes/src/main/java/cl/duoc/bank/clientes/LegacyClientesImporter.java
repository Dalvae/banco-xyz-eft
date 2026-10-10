package cl.duoc.bank.clientes;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// el mismo importador de s8 pero para la parte de clientes: de intereses.csv saco los nombres de los titulares.
// el cuenta_id no sirve como id del cliente (el mismo id aparece con varios nombres, lo vimos en el batch),
// asi que el cliente legacy queda identificado por el nombre. Unknown y vacios se descartan
@Component
public class LegacyClientesImporter implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyClientesImporter.class);
    static final String SOURCE = "legacy/intereses.csv";
    private final JdbcTemplate jdbc;

    public LegacyClientesImporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
        LOG.info("Importacion legacy clientes: lineas={} clientes legacy={}",
                jdbc.queryForObject("SELECT COUNT(*) FROM legacy_import_audit WHERE source=?", Integer.class, SOURCE),
                jdbc.queryForObject("SELECT COUNT(*) FROM customer_profile WHERE origin=?", Integer.class, SOURCE));
    }

    private void importRecord(CSVRecord record) {
        int line = Math.toIntExact(record.getRecordNumber() + 1);
        String nombre = record.get("nombre").trim();
        String reason = nombre.isEmpty() || nombre.equalsIgnoreCase("unknown") ? "NOMBRE_INVALIDO" : null;
        // insert ignore: si 2 replicas importan a la vez solo una se queda con la linea
        int nueva = jdbc.update("INSERT IGNORE INTO legacy_import_audit(source,source_line,status,reason,raw_value) VALUES(?,?,?,?,?)",
                SOURCE, line, reason == null ? "IMPORTED" : "DISCARDED", reason, nombre);
        if (nueva == 0 || reason != null) return;
        jdbc.update("INSERT IGNORE INTO customer_profile(id,display_name,segment,origin) VALUES(?,?,'LEGACY',?)",
                "legacy-" + slug(nombre), nombre, SOURCE);
    }

    private static String slug(String nombre) {
        return Normalizer.normalize(nombre.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-z0-9]+", "-");
    }
}
