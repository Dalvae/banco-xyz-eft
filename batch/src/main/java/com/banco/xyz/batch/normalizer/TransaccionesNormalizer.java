package com.banco.xyz.batch.normalizer;

import java.util.Locale;

import com.banco.xyz.batch.dtos.TransaccionesDTO;
import com.banco.xyz.batch.entities.TransaccionesEntity;
import com.banco.xyz.batch.processor.InvalidBatchDataException;

public class TransaccionesNormalizer {

    public TransaccionesEntity normalize(TransaccionesDTO dto) {
        if (dto == null) {
            throw new InvalidBatchDataException("registro_nulo", null, null, null);
        }
        String id = dto.getId() == null ? null : String.valueOf(dto.getId());

        // Una regla por motivo: el reporte tiene que decir cuál falló.
        if (dto.getId() == null) {
            throw new InvalidBatchDataException("id_ausente", id, null, null);
        }
        if (dto.getFecha() == null) {
            throw new InvalidBatchDataException("fecha_ausente", id, null, null);
        }
        if (dto.getMonto() == null) {
            throw new InvalidBatchDataException("monto_ausente", id, null, null);
        }
        if (dto.getMonto() <= 0) {
            throw new InvalidBatchDataException("monto_no_positivo", id, null, String.valueOf(dto.getMonto()));
        }
        if (dto.getTipo() == null || dto.getTipo().isBlank()) {
            throw new InvalidBatchDataException("tipo_ausente", id, null, dto.getTipo());
        }

        String tipo = dto.getTipo().trim().toLowerCase(Locale.ROOT);
        if (!"credito".equals(tipo) && !"debito".equals(tipo)) {
            throw new InvalidBatchDataException("tipo_no_permitido", id, null, dto.getTipo());
        }

        TransaccionesEntity entity = new TransaccionesEntity();
        entity.setId(dto.getId());
        entity.setFecha(dto.getFecha());
        entity.setMonto(dto.getMonto());
        entity.setTipo(tipo);
        return entity;
    }
}
