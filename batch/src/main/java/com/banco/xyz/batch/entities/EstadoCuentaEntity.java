package com.banco.xyz.batch.entities;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class EstadoCuentaEntity {
    private String datasetId;
    private String cuentaId;
    private int cantidadTransacciones;
    private Double totalIngresos;
    private Double totalRetiros;
    private LocalDate fechaProceso;
    private int anio;
    private Double movimientoNetoAnual;
}
