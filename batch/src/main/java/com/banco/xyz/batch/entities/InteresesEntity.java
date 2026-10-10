package com.banco.xyz.batch.entities;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InteresesEntity {
    
    private String datasetId;
    private Long cuentaId;
    private String nombre;
    private BigDecimal saldoBase;
    private BigDecimal tasa;
    private BigDecimal saldoConInteres;
    private Integer edad;
    private String tipo;
    private Integer sourceLine;
}
