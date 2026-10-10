package com.banco.xyz.batch.processor;

import org.springframework.batch.item.ItemProcessor;

import com.banco.xyz.batch.dtos.TransaccionesDTO;
import com.banco.xyz.batch.entities.TransaccionesEntity;
import com.banco.xyz.batch.normalizer.TransaccionesNormalizer;
import com.banco.xyz.batch.report.OmisionCollector;

public class TransaccionesProcessor implements ItemProcessor<TransaccionesDTO, TransaccionesEntity> {
    private final TransaccionesNormalizer normalizer;
    private final OmisionCollector omisiones;

    public TransaccionesProcessor(TransaccionesNormalizer normalizer, OmisionCollector omisiones) {
        this.normalizer = normalizer;
        this.omisiones = omisiones;
    }

    /** Filtra en vez de omitir: omitir revierte el chunk y lo reprocesa item por item (EVIDENCIA § 4). */
    @Override
    public TransaccionesEntity process(TransaccionesDTO dto) {
        try {
            return normalizer.normalize(dto);
        } catch (InvalidBatchDataException e) {
            omisiones.registrarFiltrado(e, dto);
            return null;
        }
    }

}
