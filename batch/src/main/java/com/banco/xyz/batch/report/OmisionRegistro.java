package com.banco.xyz.batch.report;

/** Fila descartada. Fase: lectura, proceso o escritura. Registro: el DTO o la línea cruda. */
public record OmisionRegistro(String fase, String motivo, String linea, String identificador, String valor,
        String registro) {
}
