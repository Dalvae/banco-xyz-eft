-- ms-cuentas es el unico dueno del saldo. corre en mysql (schema cuentas) y en los tests en h2 modo mysql
CREATE TABLE IF NOT EXISTS account (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner VARCHAR(40) NOT NULL,
  display_name VARCHAR(120) NOT NULL,
  masked_number VARCHAR(40) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  balance DECIMAL(19,2) NOT NULL CHECK (balance >= 0),
  status VARCHAR(10) NOT NULL,
  origin VARCHAR(80) NOT NULL,
  opened_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  closed_at TIMESTAMP NULL
);

CREATE TABLE IF NOT EXISTS movement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT NOT NULL,
  movement_date DATE NOT NULL,
  movement_type VARCHAR(30) NOT NULL,
  amount DECIMAL(19,2) NOT NULL,
  description VARCHAR(300) NOT NULL,
  source VARCHAR(100) NOT NULL,
  source_line INT,
  operation_id VARCHAR(64),
  CONSTRAINT fk_movement_account FOREIGN KEY(account_id) REFERENCES account(id),
  CONSTRAINT uq_movement_source_line UNIQUE(source, source_line),
  CONSTRAINT uq_movement_operation UNIQUE(operation_id)
);

-- cada debito/credito que pide ms-pagos queda aca con su operation_id, asi si llega 2 veces no se aplica 2 veces.
-- tipo ANULADA es cuando ms-pagos pidio la reversa de algo que nunca llego: si el debito llega tarde se rechaza
CREATE TABLE IF NOT EXISTS operacion_aplicada (
  operation_id VARCHAR(64) PRIMARY KEY,
  account_id BIGINT NULL,
  tipo VARCHAR(10) NOT NULL,
  amount DECIMAL(19,2) NOT NULL,
  balance_after DECIMAL(19,2) NOT NULL,
  revertida BOOLEAN DEFAULT FALSE NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS legacy_import_audit (
  source VARCHAR(100) NOT NULL,
  source_line INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  reason VARCHAR(80),
  raw_account VARCHAR(40),
  PRIMARY KEY(source, source_line)
);

-- las 2 cuentas de demo de s5. insert ignore para que no choque si parten 2 replicas a la vez
INSERT IGNORE INTO account(id, owner, display_name, masked_number, currency, balance, status, origin)
VALUES (101, 'demo', 'Cuenta corriente Demo', '****0101', 'CLP', 1000000.00, 'ABIERTA', 'fixture-demostracion');
INSERT IGNORE INTO account(id, owner, display_name, masked_number, currency, balance, status, origin)
VALUES (102, 'other', 'Cuenta corriente Otro', '****0102', 'CLP', 1000000.00, 'ABIERTA', 'fixture-demostracion');
