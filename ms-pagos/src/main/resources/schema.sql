-- ms-pagos guarda cada operacion con su estado (la saga) y los eventos por mandar (outbox)
-- estados: PENDIENTE -> COMPLETADA | RECHAZADA | REVERTIDA | COMPENSACION_PENDIENTE (-> REVERTIDA cuando el reconciliador la arregla,
-- o REQUIERE_REVISION si despues de 5 intentos no pudo). intentos cuenta las pasadas fallidas del reconciliador
CREATE TABLE IF NOT EXISTS operacion (
  operation_id VARCHAR(36) PRIMARY KEY,
  idempotency_key VARCHAR(120) NOT NULL UNIQUE,
  tipo VARCHAR(15) NOT NULL,
  titular VARCHAR(40) NOT NULL,
  cuenta_origen BIGINT NULL,
  cuenta_destino BIGINT NULL,
  convenio VARCHAR(60) NULL,
  monto DECIMAL(19,2) NOT NULL,
  canal VARCHAR(10) NOT NULL,
  estado VARCHAR(25) NOT NULL,
  detalle VARCHAR(200) NULL,
  saldo_origen DECIMAL(19,2) NULL,
  intentos INT DEFAULT 0 NOT NULL,
  creada_en TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  actualizada_en TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- outbox: el evento se guarda en la misma transaccion que cierra la operacion y despues se manda a kafka.
-- igual que en s8 pero ahora sirve para cualquier topico, el payload va como json
CREATE TABLE IF NOT EXISTS evento_pendiente (
  id VARCHAR(36) PRIMARY KEY,
  topico VARCHAR(60) NOT NULL,
  clave VARCHAR(60) NOT NULL,
  payload VARCHAR(2000) NOT NULL,
  creado_en TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  enviado BOOLEAN DEFAULT FALSE NOT NULL,
  intentos INT DEFAULT 0 NOT NULL
);
