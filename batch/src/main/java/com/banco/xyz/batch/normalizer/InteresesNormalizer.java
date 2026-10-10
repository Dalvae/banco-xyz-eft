package com.banco.xyz.batch.normalizer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import com.banco.xyz.batch.dtos.InteresesDTO;
import com.banco.xyz.batch.entities.InteresesEntity;
import com.banco.xyz.batch.processor.InvalidBatchDataException;

public class InteresesNormalizer {

    public InteresesEntity normalize(InteresesDTO item) {
        if (item == null) {
            throw new InvalidBatchDataException("registro_nulo", null, null, null);
        }
        String id = item.getCuentaId() == null ? null : String.valueOf(item.getCuentaId());
        Integer linea = item.getSourceLine();

        // Una regla por motivo: el reporte tiene que decir cuál falló.
        if (linea == null || linea < 1) {
            throw new InvalidBatchDataException("source_line_invalida", id, linea, String.valueOf(linea));
        }
        if (item.getCuentaId() == null) {
            throw new InvalidBatchDataException("cuenta_id_ausente", id, linea, null);
        }
        if (item.getNombre() == null || item.getNombre().isBlank()) {
            throw new InvalidBatchDataException("nombre_ausente", id, linea, item.getNombre());
        }
        if (item.getSaldo() == null) {
            throw new InvalidBatchDataException("saldo_ausente", id, linea, null);
        }
        if (item.getSaldo().signum() < 0) {
            throw new InvalidBatchDataException("saldo_negativo", id, linea, item.getSaldo().toPlainString());
        }
        if (item.getTipo() == null || item.getTipo().isBlank()) {
            throw new InvalidBatchDataException("tipo_ausente", id, linea, item.getTipo());
        }

        String tipo = item.getTipo().trim().toLowerCase(Locale.ROOT);
        // El origen no trae tasas: 5 % y 10 % son un supuesto sobre los productos del enunciado.
        // Hipoteca no tiene tasa que suponer, y guardarla en 0 diría que no genera interés.
        BigDecimal tasa = switch (tipo) {
            case "ahorro" -> new BigDecimal("0.05");
            case "prestamo" -> new BigDecimal("0.10");
            case "hipoteca" -> throw new InvalidBatchDataException("fuera_de_alcance", id, linea, item.getTipo());
            default -> throw new InvalidBatchDataException("tipo_no_permitido", id, linea, item.getTipo());
        };

        // La edad no participa en el cálculo, así que no puede vetarlo: vetaba 90 filas válidas.
        // NULL significa que no se conoce; no se imputa un valor.
        Integer edad = item.getEdad();
        if (edad != null && (edad < 0 || edad > 120)) {
            edad = null;
        }

        InteresesEntity entity = new InteresesEntity();
        entity.setCuentaId(item.getCuentaId());
        entity.setNombre(item.getNombre().trim());
        entity.setSaldoBase(item.getSaldo());
        entity.setTasa(tasa);
        entity.setSaldoConInteres(item.getSaldo().multiply(BigDecimal.ONE.add(tasa)).setScale(2, RoundingMode.HALF_UP));
        entity.setEdad(edad);
        entity.setTipo(tipo);
        entity.setSourceLine(item.getSourceLine());
        return entity;
    }
}
