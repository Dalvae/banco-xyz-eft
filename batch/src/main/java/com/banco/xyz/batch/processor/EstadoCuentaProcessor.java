package com.banco.xyz.batch.processor;

import org.springframework.batch.item.ItemProcessor;

import com.banco.xyz.batch.dtos.EstadoCuentaDTO;
import com.banco.xyz.batch.entities.EstadoCuentaMovimientoEntity;
import com.banco.xyz.batch.normalizer.EstadoCuentaNormalizer;
import com.banco.xyz.batch.report.OmisionCollector;

public class EstadoCuentaProcessor implements ItemProcessor<EstadoCuentaDTO, EstadoCuentaMovimientoEntity> {

    private final String datasetId;
    private final EstadoCuentaNormalizer normalizer;
    private final OmisionCollector omisiones;

    public EstadoCuentaProcessor(String datasetId, EstadoCuentaNormalizer normalizer, OmisionCollector omisiones) {
        this.datasetId = datasetId;
        this.normalizer = normalizer;
        this.omisiones = omisiones;
    }

    @Override
    public EstadoCuentaMovimientoEntity process(EstadoCuentaDTO item) {
        EstadoCuentaMovimientoEntity entity;
        try {
            entity = normalizer.normalize(item);
        } catch (InvalidBatchDataException e) {
            omisiones.registrarFiltrado(e, item);
            return null;
        }
        entity.setDatasetId(datasetId);
        return entity;
    }

}
