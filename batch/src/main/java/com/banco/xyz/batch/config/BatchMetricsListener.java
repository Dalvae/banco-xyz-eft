package com.banco.xyz.batch.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.banco.xyz.batch.entities.TransaccionesEntity;
import com.banco.xyz.batch.processor.InvalidBatchDataException;
import com.banco.xyz.batch.report.OmisionCollector;
import com.banco.xyz.batch.report.OmisionRegistro;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.ItemProcessListener;
import org.springframework.batch.core.ItemWriteListener;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.transform.IncorrectTokenCountException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindException;

@Component
public class BatchMetricsListener implements JobExecutionListener, SkipListener<Object, Object>,
        ItemProcessListener<Object, Object>, ItemWriteListener<Object> {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchMetricsListener.class);

    private final Map<String, Double> transactionTotals = new ConcurrentHashMap<>();
    private final Map<LocalDate, TransactionDayTotals> transactionDailyTotals = new ConcurrentHashMap<>();

    @Value("${batch.reports.output-directory:${java.io.tmpdir}/banco-xyz-batch-reportes}")
    private String reportOutputDirectory;

    private final Environment environment;
    private final OmisionCollector omisiones;
    private final JdbcTemplate jdbcTemplate;

    public BatchMetricsListener(Environment environment, OmisionCollector omisiones, JdbcTemplate jdbcTemplate) {
        this.environment = environment;
        this.omisiones = omisiones;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        omisiones.limpiar();
        transactionTotals.clear();
        transactionDailyTotals.clear();
        LOGGER.info("Inicio job={} executionId={}", jobExecution.getJobInstance().getJobName(), jobExecution.getId());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        StepExecution masterStepExecution = jobExecution.getStepExecutions().stream()
                .filter(stepExecution -> stepExecution.getStepName().endsWith("MasterStep"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No se encontró el step master de la ejecución"));
        long read = masterStepExecution.getReadCount();
        long write = masterStepExecution.getWriteCount();
        // No es el skipCount: lo filtrado en el processor cuenta como filter. El collector ve todo.
        long descartadas = omisiones.cantidad();
        long skip = masterStepExecution.getSkipCount();
        long filter = masterStepExecution.getFilterCount();
        try {
            Path directory = Path.of(reportOutputDirectory);
            Files.createDirectories(directory);
            writeSkippedReport(directory, jobExecution, jobName);
            persistirOmisiones(jobExecution, jobName);
            if ("transaccionesJob".equals(jobName)) {
                writeTransactionReport(directory, jobExecution, read, write, descartadas);
            }
        } catch (Exception e) {
            LOGGER.error("No fue posible generar el reporte CSV del job", e);
            jobExecution.setStatus(BatchStatus.FAILED);
            jobExecution.setExitStatus(ExitStatus.FAILED.addExitDescription(e));
        }
        verificarTasaDeOmision(jobExecution, jobName, masterStepExecution, descartadas);
        LOGGER.info("Fin job={} status={} read={} write={} descartadas={} (filtradas={} omitidas={})", jobName,
                jobExecution.getStatus(), read, write, descartadas, filter, skip);
    }

    /** Al cierre del job y en un solo batch: son subproducto del reporte, no dato de dominio. */
    private void persistirOmisiones(JobExecution jobExecution, String jobName) {
        List<OmisionRegistro> registros = omisiones.ordenadasPorLinea();
        if (registros.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO omitidos (job_execution_id, job_name, fase, motivo, linea, identificador, valor, registro) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                registros, registros.size(), (statement, omision) -> {
                    statement.setLong(1, jobExecution.getId());
                    statement.setString(2, jobName);
                    statement.setString(3, omision.fase());
                    statement.setString(4, omision.motivo());
                    Integer linea = parseLineaOrNull(omision.linea());
                    if (linea == null) {
                        statement.setNull(5, java.sql.Types.INTEGER);
                    } else {
                        statement.setInt(5, linea);
                    }
                    statement.setString(6, recortar(omision.identificador(), 128));
                    statement.setString(7, recortar(omision.valor(), 255));
                    statement.setString(8, omision.registro());
                });
        LOGGER.info("Omitidos persistidos en la tabla omitidos: {} filas (job_execution_id={})", registros.size(),
                jobExecution.getId());
    }

    private Integer parseLineaOrNull(String linea) {
        try {
            return Integer.valueOf(linea);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String recortar(String valor, int largo) {
        if (valor == null) {
            return null;
        }
        return valor.length() <= largo ? valor : valor.substring(0, largo);
    }

    /**
     * El umbral detecta que el archivo se degradó, no que venga sucio: está calibrado sobre el
     * baseline de cada job. Se evalúa al terminar, así que señaliza la corrida pero no la revierte.
     */
    private void verificarTasaDeOmision(JobExecution jobExecution, String jobName, StepExecution masterStepExecution,
            long skip) {
        // readCount no cuenta las lecturas fallidas; hay que sumarlas para tener el total real.
        long origen = masterStepExecution.getReadCount() + masterStepExecution.getReadSkipCount();
        if (origen == 0) {
            return;
        }
        double umbral = umbralPara(jobName);
        double ratio = (double) skip / origen;
        if (ratio <= umbral) {
            LOGGER.info("Tasa de omisión job={} {}/{} = {} (umbral {})", jobName, skip, origen,
                    String.format("%.3f", ratio), umbral);
            advertirSiEsAlta(jobName, ratio, skip, origen);
            return;
        }
        String detalle = String.format(
                "Tasa de omisión %.1f%% (%d de %d registros) supera el umbral %.1f%% de %s",
                ratio * 100, skip, origen, umbral * 100, jobName);
        LOGGER.error("job={} {}", jobName, detalle);
        jobExecution.setStatus(BatchStatus.FAILED);
        jobExecution.setExitStatus(ExitStatus.FAILED.addExitDescription(detalle));
    }

    /** Una tasa esperada no es una tasa aceptable: sobre el aviso deja WARN sin fallar el job. */
    private void advertirSiEsAlta(String jobName, double ratio, long descartadas, long origen) {
        double aviso = environment.getProperty("batch.skip.warn-ratio", Double.class, 0.25);
        if (ratio <= aviso) {
            return;
        }
        LOGGER.warn("job={} descartó {} de {} registros ({}%): dentro del umbral, pero es una "
                + "proporción alta del origen. Revisar el detalle de omitidos antes de dar la carga por buena.",
                jobName, descartadas, origen, String.format("%.1f", ratio * 100));
    }

    private double umbralPara(String jobName) {
        Double propio = environment.getProperty("batch.skip.max-ratio." + jobName, Double.class);
        return propio != null ? propio : environment.getProperty("batch.skip.max-ratio", Double.class, 0.80);
    }

    @Override
    public void afterWrite(Chunk<? extends Object> items) {
        for (Object item : items) {
            if (item instanceof TransaccionesEntity transaction) {
                transactionTotals.merge(transaction.getTipo(), transaction.getMonto(), Double::sum);
                transactionDailyTotals.computeIfAbsent(transaction.getFecha(), ignored -> new TransactionDayTotals())
                        .add(transaction.getTipo(), transaction.getMonto());
            }
        }
    }

    @Override
    public void onSkipInRead(Throwable throwable) {
        // En lectura todavía no hay DTO, pero FlatFileParseException trae la línea cruda y su número.
        FlatFileParseException parseException = buscarCausa(throwable, FlatFileParseException.class);
        String linea = parseException == null ? "" : String.valueOf(parseException.getLineNumber());
        String registro = parseException == null ? safeMessage(throwable) : parseException.getInput();
        omisiones.registrar(new OmisionRegistro("lectura", motivoDeMapeo(throwable), linea, "",
                valorRechazado(throwable), registro));
    }

    /** Ya casi no se dispara: las inválidas de negocio las filtra el processor. Queda como red. */
    @Override
    public void onSkipInProcess(Object item, Throwable throwable) {
        omisiones.registrar(construirOmision("proceso", item, throwable));
    }

    @Override
    public void onSkipInWrite(Object item, Throwable throwable) {
        omisiones.registrar(construirOmision("escritura", item, throwable));
    }

    @Override public void afterProcess(Object item, Object result) { }

    @Override public void beforeProcess(Object item) { }
    @Override public void onProcessError(Object item, Exception exception) { }
    @Override public void beforeWrite(Chunk<? extends Object> items) { }
    @Override public void onWriteError(Exception exception, Chunk<? extends Object> items) { }

    private OmisionRegistro construirOmision(String fase, Object item, Throwable throwable) {
        InvalidBatchDataException invalida = buscarCausa(throwable, InvalidBatchDataException.class);
        if (invalida == null) {
            return new OmisionRegistro(fase, motivoDeMapeo(throwable), "", "", valorRechazado(throwable),
                    String.valueOf(item));
        }
        return new OmisionRegistro(fase, invalida.getMotivo(),
                invalida.getSourceLine() == null ? "" : String.valueOf(invalida.getSourceLine()),
                invalida.getIdentificador() == null ? "" : invalida.getIdentificador(),
                invalida.getValor() == null ? "" : invalida.getValor(),
                String.valueOf(item));
    }

    /**
     * Fallas de mapeo, anteriores al normalizer. Ojo: BindException no encadena la causa en
     * {@code getCause()} sino en FieldError, y sin mirar ahí todo sale como "línea ilegible".
     */
    private String motivoDeMapeo(Throwable throwable) {
        BindException bind = buscarCausa(throwable, BindException.class);
        if (bind != null && !bind.getFieldErrors().isEmpty()) {
            return "campo_invalido_" + bind.getFieldErrors().get(0).getField();
        }
        if (buscarCausa(throwable, DateTimeParseException.class) != null) {
            return "fecha_invalida";
        }
        if (buscarCausa(throwable, NumberFormatException.class) != null) {
            return "numero_invalido";
        }
        if (buscarCausa(throwable, IncorrectTokenCountException.class) != null) {
            return "columnas_incompletas";
        }
        return "linea_ilegible";
    }

    private String valorRechazado(Throwable throwable) {
        BindException bind = buscarCausa(throwable, BindException.class);
        if (bind != null && !bind.getFieldErrors().isEmpty()) {
            return String.valueOf(bind.getFieldErrors().get(0).getRejectedValue());
        }
        return safeMessage(throwable);
    }

    private <T extends Throwable> T buscarCausa(Throwable throwable, Class<T> tipo) {
        Throwable actual = throwable;
        while (actual != null) {
            if (tipo.isInstance(actual)) {
                return tipo.cast(actual);
            }
            actual = actual.getCause();
        }
        return null;
    }

    private void writeSkippedReport(Path directory, JobExecution execution, String jobName) throws IOException {
        String prefijo = jobName.endsWith("Job") ? jobName.substring(0, jobName.length() - 3) : jobName;

        List<OmisionRegistro> ordenadas = omisiones.ordenadasPorLinea();

        List<String> detalle = new ArrayList<>();
        detalle.add("fase,motivo,linea,identificador,valor,registro");
        for (OmisionRegistro omision : ordenadas) {
            detalle.add(String.join(",", csv(omision.fase()), csv(omision.motivo()), csv(omision.linea()),
                    csv(omision.identificador()), csv(omision.valor()), csv(omision.registro())));
        }
        Path detalleOutput = directory.resolve(prefijo + "-" + execution.getId() + "-omitidos.csv");
        Files.write(detalleOutput, detalle, StandardCharsets.UTF_8);

        Map<String, Long> porMotivo = ordenadas.stream().collect(Collectors.groupingBy(
                omision -> omision.fase() + "," + omision.motivo(), LinkedHashMap::new, Collectors.counting()));
        List<String> resumen = new ArrayList<>();
        resumen.add("fase,motivo,cantidad");
        porMotivo.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> resumen.add(entry.getKey() + "," + entry.getValue()));
        Path resumenOutput = directory.resolve(prefijo + "-" + execution.getId() + "-omitidos-resumen.csv");
        Files.write(resumenOutput, resumen, StandardCharsets.UTF_8);

        LOGGER.info("Omitidos job={} total={} detalle={} resumen={}", jobName, ordenadas.size(), detalleOutput,
                resumenOutput);
        porMotivo.forEach((clave, cantidad) -> LOGGER.info("  omitidos {} -> {}", clave, cantidad));
    }

    private void writeTransactionReport(Path directory, JobExecution execution, long read, long write, long skip)
            throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("seccion,clave,valor");
        lines.add("ejecucion,id," + execution.getId());
        lines.add("ejecucion,estado," + execution.getStatus());
        lines.add("conteo,leidos," + read);
        lines.add("conteo,escritos," + write);
        lines.add("conteo,omitidos," + skip);
        double creditos = transactionTotals.getOrDefault("credito", 0.0);
        double debitos = transactionTotals.getOrDefault("debito", 0.0);
        lines.add("total,credito," + creditos);
        lines.add("total,debito," + debitos);
        lines.add("total,neto," + (creditos - debitos));
        lines.add("dia,fecha,creditos,debito,neto,conteo");
        transactionDailyTotals.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            TransactionDayTotals totals = entry.getValue();
            lines.add("dia," + entry.getKey() + ',' + totals.getCreditos() + ',' + totals.getDebitos() + ','
                    + totals.getNeto() + ',' + totals.getConteo());
        });
        Path output = directory.resolve("transacciones-" + execution.getId() + "-resumen.csv");
        Files.write(output, lines, StandardCharsets.UTF_8);
        LOGGER.info("Resumen de transacciones generado en {}", output);
    }

    private String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private String csv(String value) {
        return '"' + String.valueOf(value).replace("\"", "\"\"") + '"';
    }

    private static final class TransactionDayTotals {
        private double creditos;
        private double debitos;
        private long conteo;

        synchronized void add(String tipo, double monto) {
            if ("credito".equals(tipo)) {
                creditos += monto;
            } else {
                debitos += monto;
            }
            conteo++;
        }

        synchronized double getCreditos() { return creditos; }
        synchronized double getDebitos() { return debitos; }
        synchronized double getNeto() { return creditos - debitos; }
        synchronized long getConteo() { return conteo; }
    }
}
