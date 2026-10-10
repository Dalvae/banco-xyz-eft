package com.banco.xyz.batch.report;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.stereotype.Component;

import com.banco.xyz.batch.processor.InvalidBatchDataException;

/**
 * Junta las descartadas, que llegan por dos vías: filtradas en el processor, y las de lectura, que
 * revientan antes de que exista un item que filtrar. Lo escriben varias particiones en paralelo.
 */
@Component
public class OmisionCollector {

    private final ConcurrentLinkedQueue<OmisionRegistro> omisiones = new ConcurrentLinkedQueue<>();

    public void registrarFiltrado(InvalidBatchDataException causa, Object item) {
        omisiones.add(new OmisionRegistro("proceso", causa.getMotivo(),
                causa.getSourceLine() == null ? "" : String.valueOf(causa.getSourceLine()),
                causa.getIdentificador() == null ? "" : causa.getIdentificador(),
                causa.getValor() == null ? "" : causa.getValor(),
                String.valueOf(item)));
    }

    public void registrar(OmisionRegistro omision) {
        omisiones.add(omision);
    }

    public List<OmisionRegistro> ordenadasPorLinea() {
        return omisiones.stream().sorted(Comparator.comparingLong(OmisionCollector::parseLinea)).toList();
    }

    public int cantidad() {
        return omisiones.size();
    }

    public void limpiar() {
        omisiones.clear();
    }

    /** Sin línea identificable van al final, no rompen el orden. */
    private static long parseLinea(OmisionRegistro omision) {
        try {
            return Long.parseLong(omision.linea());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
