package com.banco.xyz.batch.processor;

import org.springframework.batch.item.ItemProcessor;

import com.banco.xyz.batch.dtos.InteresesDTO;
import com.banco.xyz.batch.entities.InteresesEntity;
import com.banco.xyz.batch.normalizer.InteresesNormalizer;
import com.banco.xyz.batch.report.OmisionCollector;

public class InteresesProcessor implements ItemProcessor<InteresesDTO, InteresesEntity> {
    private final String datasetId;
    private final InteresesNormalizer normalizer;
    private final OmisionCollector omisiones;

    public InteresesProcessor(String datasetId, InteresesNormalizer normalizer, OmisionCollector omisiones) {
        this.datasetId = datasetId;
        this.normalizer = normalizer;
        this.omisiones = omisiones;
    }

    @Override
    public InteresesEntity process(InteresesDTO item) {
        InteresesEntity entity;
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
