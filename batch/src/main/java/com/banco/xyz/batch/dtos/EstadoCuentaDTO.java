package com.banco.xyz.batch.dtos;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EstadoCuentaDTO {

    private String cuentaId;
    private LocalDate fecha;
    private String transaccion;
    private Double monto;
    private String descripcion;
    private Integer sourceLine;

}
