package com.banco.xyz.batch.partitioner;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.core.io.Resource;

public class CsvPartitioner implements Partitioner {

    private static final Logger LOGGER = LoggerFactory.getLogger(CsvPartitioner.class);

    private final Resource archivo;

    public CsvPartitioner(Resource archivo) {
        this.archivo = archivo;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> partitions = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(archivo.getInputStream(), StandardCharsets.UTF_8))) {
            long lines = reader.lines().count();
            int totalLines = Math.toIntExact(Math.max(0, lines - 1));
            if (totalLines == 0) {
                LOGGER.warn("El recurso {} no contiene registros para particionar", archivo.getDescription());
                return partitions;
            }

            int partitionCount = Math.min(Math.max(1, gridSize), totalLines);
            int baseSize = totalLines / partitionCount;
            int remainder = totalLines % partitionCount;
            int startLine = 1; // omite cabecera y registros ya asignados.

            for (int i = 0; i < partitionCount; i++) {
                ExecutionContext context = new ExecutionContext();
                int count = baseSize + (i < remainder ? 1 : 0);
                context.put("startLine", startLine);
                context.put("maxItems", count);
                partitions.put("partition-" + archivo.getFilename() + "-" + i, context);
                startLine += count;
            }

        } catch (Exception e) {
            LOGGER.error("Error al generar las particiones", e);
            throw new RuntimeException("Error al generar las particiones", e);
        }

        return partitions;
    }

}
