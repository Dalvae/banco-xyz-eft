#!/usr/bin/env bash
# demo de resilience4j para el video: retry, circuit breaker y fallback cuando se cae ms-cuentas.
# hay que tener todo arriba (docker-compose up -d). uso: scripts/demo-resiliencia.sh
# los actuator de los bff y ms-pagos no tienen puerto hacia afuera, por eso los consulto con exec + wget
set -uo pipefail
cd "$(dirname "$0")/.."

# en mi mac es docker-compose, en linux casi siempre docker compose
if docker compose version >/dev/null 2>&1; then DC="docker compose"; else DC="docker-compose"; fi
G=https://localhost:8443

titulo() { echo; echo "== $* =="; }
actuator() { $DC exec -T "$1" wget -qO- "http://localhost:$2/actuator/$3"; }
# saca solo nombre y estado de cada circuito (el json completo es muy largo para la pantalla)
estados() {
  actuator "$1" "$2" circuitbreakers | python3 -c '
import json, sys
for nombre, cb in json.load(sys.stdin)["circuitBreakers"].items():
    print("   %-9s %-10s %-10s fallas=%s rechazadas=%s" % (sys.argv[1], nombre, cb["state"], cb["failedCalls"], cb["notPermittedCalls"]))' "$1"
}
estado_web() { actuator bff-web 8441 circuitbreakers | python3 -c 'import json,sys; print(json.load(sys.stdin)["circuitBreakers"]["msCuentas"]["state"])'; }
dashboard() { curl -sk -w "  [%{http_code} en %{time_total}s]\n" -H "Authorization: Bearer $WEB" $G/api/web/me/dashboard | cut -c1-170; }

WEB=$(scripts/token.sh web)
ATM=$(scripts/token.sh atm)
if [ -z "$WEB" ] || [ -z "$ATM" ]; then echo "no pude sacar los tokens, esta todo arriba?"; exit 1; fi

titulo "1. todo sano, circuito CLOSED"
# una llamada a cada uno para que los circuitos existan (resilience4j los crea en la primera llamada)
dashboard
curl -sk -o /dev/null -H "Authorization: Bearer $WEB" -H "Idempotency-Key: demo-ok-$(date +%s)" -H 'Content-Type: application/json' \
     -d '{"fromAccount":101,"toAccount":102,"amount":1000}' $G/api/web/transferencias
estados bff-web 8441
estados ms-pagos 8082

titulo "2. apago ms-cuentas"
$DC stop ms-cuentas 2>&1 | grep -v "^$"

titulo "3. retry: una llamada al dashboard y una transferencia, cada una se intenta 3 veces"
dashboard
curl -sk -w "  [%{http_code}]\n" -H "Authorization: Bearer $WEB" -H "Idempotency-Key: demo-caido-$(date +%s)" -H 'Content-Type: application/json' \
     -d '{"fromAccount":101,"toAccount":102,"amount":1000}' $G/api/web/transferencias | cut -c1-200
echo "-- /actuator/retryevents de bff-web (ultimos 3):"
actuator bff-web 8441 retryevents/msCuentas | python3 -c '
import json, sys
for e in json.load(sys.stdin)["retryEvents"][-3:]:
    print("  ", e["creationTime"][11:23], e["retryName"], e["type"], "intento", e["numberOfAttempts"], "-", e["errorMessage"][:70])'
# ms-pagos sigue reintentando un poco despues de que el bff ya respondio, le doy un par de segundos
sleep 3
echo "-- /actuator/retryevents de ms-pagos (ultimos 3):"
actuator ms-pagos 8082 retryevents/msCuentas | python3 -c '
import json, sys
for e in json.load(sys.stdin)["retryEvents"][-3:]:
    print("  ", e["creationTime"][11:23], e["retryName"], e["type"], "intento", e["numberOfAttempts"], "-", e["errorMessage"][:70])'

titulo "4. sigo llamando hasta que el circuito se abre (OPEN) y responde el fallback"
for i in $(seq 1 8); do
  dashboard
  [ "$(estado_web)" = "OPEN" ] && break
done
echo "-- con el circuito abierto ya ni intenta, el fallback responde al tiro:"
dashboard
echo "-- el cajero no inventa un saldo, responde 503:"
curl -sk -w "  [%{http_code}]\n" -H "Authorization: Bearer $ATM" $G/api/atm/cuentas/101/saldo
estados bff-web 8441
estados bff-atm 8444
estados ms-pagos 8082
echo "-- /actuator/circuitbreakers de bff-web completo:"
actuator bff-web 8441 circuitbreakers | python3 -m json.tool

titulo "5. vuelvo a levantar ms-cuentas, espero HALF_OPEN y llamo hasta que se cierra"
$DC start ms-cuentas 2>&1 | grep -E "Started|Running"
until $DC ps ms-cuentas --format '{{.Status}}' | grep -q '(healthy)'; do sleep 3; done
echo "ms-cuentas healthy"
until [ "$(estado_web)" = "HALF_OPEN" ]; do sleep 2; done
estados bff-web 8441
# le doy unos segundos al loadbalancer para que vea la instancia nueva en eureka
sleep 10
for i in $(seq 1 6); do
  dashboard
  [ "$(estado_web)" = "CLOSED" ] && break
  sleep 2
done
estados bff-web 8441
