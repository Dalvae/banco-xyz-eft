CREATE TABLE IF NOT EXISTS transacciones (
    id BIGINT NOT NULL,
    fecha DATE NOT NULL,
    monto DOUBLE NOT NULL,
    tipo VARCHAR(16) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_transacciones_fecha_tipo (fecha, tipo)
);

CREATE TABLE IF NOT EXISTS intereses (
    dataset_id VARCHAR(128) NOT NULL,
    source_line INT NOT NULL,
    cuenta_id BIGINT NOT NULL,
    nombre VARCHAR(255) NOT NULL,
    saldo_base DECIMAL(19,2) NOT NULL,
    tasa DECIMAL(5,4) NOT NULL,
    saldo_con_interes DECIMAL(19,2) NOT NULL,
    -- NULL = el origen no trajo la edad o trajo un valor imposible. No participa en el cálculo.
    edad INT NULL,
    tipo VARCHAR(32) NOT NULL,
    PRIMARY KEY (dataset_id, source_line),
    INDEX idx_intereses_dataset_cuenta (dataset_id, cuenta_id)
);

CREATE TABLE IF NOT EXISTS estado_cuenta_movimiento (
    dataset_id VARCHAR(128) NOT NULL,
    source_key VARCHAR(255) NOT NULL,
    cuenta_id VARCHAR(128) NOT NULL,
    fecha_proceso DATE NOT NULL,
    anio INT NOT NULL,
    tipo_movimiento VARCHAR(32) NOT NULL,
    total_ingresos DOUBLE NOT NULL,
    total_retiros DOUBLE NOT NULL,
    movimiento_neto_anual DOUBLE NOT NULL,
    PRIMARY KEY (dataset_id, source_key),
    INDEX idx_estado_movimiento_dataset_cuenta_anio (dataset_id, cuenta_id, anio),
    INDEX idx_estado_movimiento_dataset_source (dataset_id, source_key)
);

-- Filas que el origen no permitió procesar, para auditarlas con SQL y no sólo abriendo el CSV.
-- Viven aparte de las tablas de dominio: no son transacciones ni cuentas, y mezclarlas obligaría a
-- filtrar en cada consulta para no contar como dato algo que el origen nunca trajo.
CREATE TABLE IF NOT EXISTS omitidos (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_execution_id BIGINT NOT NULL,
    job_name VARCHAR(64) NOT NULL,
    fase VARCHAR(16) NOT NULL,
    motivo VARCHAR(64) NOT NULL,
    -- NULL cuando la omisión no proviene de una línea identificable del CSV.
    linea INT NULL,
    identificador VARCHAR(128) NULL,
    valor VARCHAR(255) NULL,
    registro TEXT NULL,
    PRIMARY KEY (id),
    INDEX idx_omitidos_job (job_name, job_execution_id),
    INDEX idx_omitidos_motivo (job_name, motivo)
);

CREATE TABLE IF NOT EXISTS estado_cuenta (
    dataset_id VARCHAR(128) NOT NULL,
    cuenta_id VARCHAR(128) NOT NULL,
    cantidad_transacciones INT NOT NULL,
    total_ingresos DOUBLE NOT NULL,
    total_retiros DOUBLE NOT NULL,
    fecha_proceso DATE NOT NULL,
    anio INT NOT NULL,
    movimiento_neto_anual DOUBLE NOT NULL,
    PRIMARY KEY (dataset_id, cuenta_id, anio),
    INDEX idx_estado_cuenta_dataset_anio (dataset_id, anio)
);
