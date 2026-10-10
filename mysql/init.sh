#!/bin/bash
# corre una sola vez, cuando el volumen de mysql esta vacio (docker-entrypoint-initdb.d).
# database per service: cada microservicio tiene su schema y un usuario que solo ve ese schema.
# es el mismo servidor mysql para no gastar ram, pero ms-pagos no puede leer las tablas de ms-cuentas
set -e
mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS cuentas;
CREATE DATABASE IF NOT EXISTS pagos;
CREATE DATABASE IF NOT EXISTS clientes;
CREATE DATABASE IF NOT EXISTS notificaciones;
CREATE DATABASE IF NOT EXISTS banco_xyz_batch;
CREATE USER IF NOT EXISTS 'cuentas'@'%' IDENTIFIED BY '$DB_PASSWORD';
CREATE USER IF NOT EXISTS 'pagos'@'%' IDENTIFIED BY '$DB_PASSWORD';
CREATE USER IF NOT EXISTS 'clientes'@'%' IDENTIFIED BY '$DB_PASSWORD';
CREATE USER IF NOT EXISTS 'notificaciones'@'%' IDENTIFIED BY '$DB_PASSWORD';
CREATE USER IF NOT EXISTS 'batch'@'%' IDENTIFIED BY '$DB_PASSWORD';
GRANT ALL ON cuentas.* TO 'cuentas'@'%';
GRANT ALL ON pagos.* TO 'pagos'@'%';
GRANT ALL ON clientes.* TO 'clientes'@'%';
GRANT ALL ON notificaciones.* TO 'notificaciones'@'%';
GRANT ALL ON banco_xyz_batch.* TO 'batch'@'%';
FLUSH PRIVILEGES;
SQL
