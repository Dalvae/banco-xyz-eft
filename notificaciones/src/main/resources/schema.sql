-- una fila por evento recibido (transaccion o alerta)
CREATE TABLE IF NOT EXISTS notificacion (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  evento_id VARCHAR(36) NOT NULL,
  topico VARCHAR(60) NOT NULL,
  tipo VARCHAR(30) NOT NULL,
  cuenta_id BIGINT,
  titular VARCHAR(40) NOT NULL,
  mensaje VARCHAR(300) NOT NULL,
  recibido_en TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  -- con el outbox un evento puede llegar 2 veces, asi no se guarda repetido
  CONSTRAINT uq_notificacion_evento UNIQUE(evento_id)
);
