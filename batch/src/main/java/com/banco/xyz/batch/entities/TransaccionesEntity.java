package com.banco.xyz.batch.entities;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransaccionesEntity {
    private Long id;
    private LocalDate fecha;
    private Double monto;
    private String tipo;
}
