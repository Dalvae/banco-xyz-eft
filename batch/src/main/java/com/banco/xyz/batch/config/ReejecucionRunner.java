package com.banco.xyz.batch.config;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

// reejecucion automatica ante fallo critico (lo que pedia la parte 1 de la eft).
// los fallos chicos ya estaban cubiertos: datos malos -> skip, bd que se cae un rato -> retry del chunk (3 veces).
// si igual un step termina FAILED (la bd no volvio, un error que no es de datos) el job se relanza con los
// MISMOS parametros: para spring batch eso es un restart de la misma instancia, asi que sigue desde el ultimo
// chunk confirmado y no reprocesa lo que ya estaba escrito.
// tambien si el contenedor murio a la mitad: al partir revisa si la ultima corrida quedo FAILED y la retoma.
@Component
public class ReejecucionRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(ReejecucionRunner.class);

    private final Map<String, Job> jobs;
    private final JobLauncher launcher;
    private final JobExplorer explorer;
    private final String nombre;
    private final int maxIntentos;
    private final long esperaMs;
    private int exitCode;

    public ReejecucionRunner(Map<String, Job> jobs, JobLauncher launcher, JobExplorer explorer,
            @Value("${spring.batch.job.name}") String nombre,
            @Value("${batch.reejecucion.max-intentos:3}") int maxIntentos,
            @Value("${batch.reejecucion.espera-ms:2000}") long esperaMs) {
        this.jobs = jobs;
        this.launcher = launcher;
        this.explorer = explorer;
        this.nombre = nombre;
        this.maxIntentos = maxIntentos;
        this.esperaMs = esperaMs;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Job job = jobs.get(nombre);
        if (job == null) {
            throw new IllegalArgumentException("No existe el job " + nombre + ", los que hay: " + jobs.keySet());
        }
        JobParameters parametros = parametros(job);
        JobExecution ejecucion = null;
        for (int intento = 1; intento <= maxIntentos; intento++) {
            ejecucion = launcher.run(job, parametros);
            if (ejecucion.getStatus() == BatchStatus.COMPLETED) {
                break;
            }
            if (!fallo(ejecucion)) {
                // los steps terminaron bien pero el job quedo FAILED: es el umbral de omision o el reporte.
                // reintentar no sirve porque el csv va a seguir igual, eso lo tiene que revisar alguien
                LOG.error("job={} FAILED sin step caido ({}), no se reejecuta", nombre,
                        ejecucion.getExitStatus().getExitDescription());
                break;
            }
            if (intento < maxIntentos) {
                long espera = esperaMs * intento;
                LOG.warn("job={} executionId={} FAILED en un step, reejecuto en {} ms (intento {} de {})", nombre,
                        ejecucion.getId(), espera, intento + 1, maxIntentos);
                Thread.sleep(espera);
            }
        }
        exitCode = ejecucion.getStatus() == BatchStatus.COMPLETED ? 0 : 1;
        LOG.info("job={} termino con status={} exit={}", nombre, ejecucion.getStatus(), exitCode);
    }

    // si la corrida anterior se cayo a la mitad la retomo, si no parto una instancia nueva (run.id + 1)
    private JobParameters parametros(Job job) {
        JobInstance ultima = explorer.getLastJobInstance(nombre);
        if (ultima != null) {
            JobExecution anterior = explorer.getLastJobExecution(ultima);
            if (anterior != null && anterior.getStatus() == BatchStatus.FAILED && fallo(anterior)) {
                LOG.warn("job={} la ejecucion {} quedo FAILED, la retomo desde donde quedo", nombre, anterior.getId());
                return anterior.getJobParameters();
            }
        }
        return new JobParametersBuilder(explorer).getNextJobParameters(job).toJobParameters();
    }

    private static boolean fallo(JobExecution ejecucion) {
        return ejecucion.getStepExecutions().stream().anyMatch(step -> step.getStatus() == BatchStatus.FAILED);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
