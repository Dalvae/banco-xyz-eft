package com.banco.xyz.batch.normalizer;

import java.text.Normalizer;
import java.util.Locale;

import com.banco.xyz.batch.dtos.EstadoCuentaDTO;
import com.banco.xyz.batch.entities.EstadoCuentaMovimientoEntity;
import com.banco.xyz.batch.processor.InvalidBatchDataException;

public class EstadoCuentaNormalizer {

    public EstadoCuentaMovimientoEntity normalize(EstadoCuentaDTO item) {
        if (item == null) {
            throw new InvalidBatchDataException("registro_nulo", null, null, null);
        }
        String id = item.getCuentaId() == null ? null : item.getCuentaId().trim();
        Integer linea = item.getSourceLine();

        // Una regla por motivo: el reporte tiene que decir cuál falló.
        if (linea == null || linea < 1) {
            throw new InvalidBatchDataException("source_line_invalida", id, linea, String.valueOf(linea));
        }
        if (item.getCuentaId() == null || item.getCuentaId().isBlank()) {
            throw new InvalidBatchDataException("cuenta_id_ausente", id, linea, item.getCuentaId());
        }
        if (item.getFecha() == null) {
            throw new InvalidBatchDataException("fecha_ausente", id, linea, null);
        }
        if (item.getMonto() == null) {
            throw new InvalidBatchDataException("monto_ausente", id, linea, null);
        }
        if (item.getMonto() <= 0) {
            throw new InvalidBatchDataException("monto_no_positivo", id, linea, String.valueOf(item.getMonto()));
        }
        if (item.getTransaccion() == null || item.getTransaccion().isBlank()) {
            throw new InvalidBatchDataException("transaccion_ausente", id, linea, item.getTransaccion());
        }

        String tipo = Normalizer.normalize(item.getTransaccion().trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        if (!"deposito".equals(tipo) && !"retiro".equals(tipo) && !"compra".equals(tipo) && !"pago".equals(tipo)) {
            throw new InvalidBatchDataException("tipo_no_permitido", id, linea, item.getTransaccion());
        }

        EstadoCuentaMovimientoEntity entity = new EstadoCuentaMovimientoEntity();
        entity.setSourceKey(id + "|" + item.getSourceLine());
        entity.setCuentaId(id);
        entity.setFechaProceso(item.getFecha());
        entity.setAnio(item.getFecha().getYear());
        entity.setTipoMovimiento(tipo);
        entity.setTotalIngresos("deposito".equals(tipo) ? item.getMonto() : 0.0);
        entity.setTotalRetiros("deposito".equals(tipo) ? 0.0 : item.getMonto());
        entity.setMovimientoNetoAnual("deposito".equals(tipo) ? item.getMonto() : -item.getMonto());
        return entity;
    }
}
