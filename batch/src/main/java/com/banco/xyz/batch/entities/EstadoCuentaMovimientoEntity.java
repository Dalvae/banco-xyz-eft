package com.banco.xyz.batch.entities;

import java.time.LocalDate;

import lombok.Data;

@Data
public class EstadoCuentaMovimientoEntity {
    private String datasetId;
    private String sourceKey;
    private String cuentaId;
    private LocalDate fechaProceso;
    private int anio;
    private String tipoMovimiento;
    private Double totalIngresos;
    private Double totalRetiros;
    private Double movimientoNetoAnual;
}
