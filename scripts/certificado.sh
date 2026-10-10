#!/usr/bin/env bash
# genera el certificado autofirmado del gateway (pkcs12) para correrlo fuera de docker.
# en docker no hace falta, el Dockerfile del gateway hace lo mismo con keytool
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .runtime
rm -f .runtime/gateway.p12
keytool -genkeypair -alias gateway -keyalg RSA -keysize 2048 -validity 365 \
  -storetype PKCS12 -keystore .runtime/gateway.p12 -storepass "${GATEWAY_KEYSTORE_PASSWORD:-changeit}" \
  -dname "CN=localhost, OU=EFT, O=Banco XYZ, L=Santiago, C=CL" -ext "SAN=dns:localhost,ip:127.0.0.1"
echo "listo: .runtime/gateway.p12"
