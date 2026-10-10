package com.banco.xyz.batch.processor;

/** Dato de negocio inválido. El motivo es desagregado para que el reporte diga qué falló. */
public class InvalidBatchDataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String motivo;
    private final String identificador;
    private final Integer sourceLine;
    private final String valor;

    public InvalidBatchDataException(String motivo, String identificador, Integer sourceLine, String valor) {
        super(construirMensaje(motivo, identificador, sourceLine, valor));
        this.motivo = motivo;
        this.identificador = identificador;
        this.sourceLine = sourceLine;
        this.valor = valor;
    }

    private static String construirMensaje(String motivo, String identificador, Integer sourceLine, String valor) {
        return motivo + " (linea=" + sourceLine + ", registro=" + identificador + ", valor=" + valor + ")";
    }

    public String getMotivo() {
        return motivo;
    }

    public String getIdentificador() {
        return identificador;
    }

    public Integer getSourceLine() {
        return sourceLine;
    }

    public String getValor() {
        return valor;
    }
}
