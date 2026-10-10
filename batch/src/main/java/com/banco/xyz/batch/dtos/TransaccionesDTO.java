package com.banco.xyz.batch.dtos;

import java.time.LocalDate;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransaccionesDTO {
    private Long id;
    private LocalDate fecha;
    private Double monto;
    private String tipo;
}
