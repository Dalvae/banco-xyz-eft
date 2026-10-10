-- ms-clientes: datos personales y perfil. antes customer_profile estaba en bank-api junto con las cuentas
CREATE TABLE IF NOT EXISTS customer_profile (
  id VARCHAR(40) PRIMARY KEY,
  display_name VARCHAR(120) NOT NULL,
  email VARCHAR(120),
  phone VARCHAR(20),
  segment VARCHAR(20) NOT NULL,
  origin VARCHAR(60) NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS legacy_import_audit (
  source VARCHAR(100) NOT NULL,
  source_line INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  reason VARCHAR(80),
  raw_value VARCHAR(120),
  PRIMARY KEY(source, source_line)
);

-- los usuarios del auth-server (demo y other)
INSERT IGNORE INTO customer_profile(id, display_name, email, phone, segment, origin)
VALUES ('demo', 'Cliente Demo', 'demo@bancoxyz.cl', '+56911111111', 'PERSONAS', 'fixture-demostracion');
INSERT IGNORE INTO customer_profile(id, display_name, email, phone, segment, origin)
VALUES ('other', 'Otro Cliente', 'other@bancoxyz.cl', '+56922222222', 'PERSONAS', 'fixture-demostracion');
