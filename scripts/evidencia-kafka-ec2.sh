#!/usr/bin/env bash
# junta la evidencia de que kafka corre en la ec2 y que mis microservicios (locales) lo usan.
# deja todo en evidencias/kafka-ec2.txt (con --local en evidencias/kafka-ec2-simulado-local.txt)
#
# uso:   scripts/evidencia-kafka-ec2.sh <ip_publica> <ruta_pem>
#        scripts/evidencia-kafka-ec2.sh 127.0.0.1 - --local    (prueba sin aws ni ssh, con un kafka local que simula la ec2)
#
# antes: correr infra/ansible (provision.yml + deploy.yml), poner KAFKA_BOOTSTRAP=<ip>:9094 en el .env,
# sacar kafka-local de COMPOSE_PROFILES y levantar el compose (docker-compose up -d)
set -uo pipefail
cd "$(dirname "$0")/.."

IP=${1:?uso: evidencia-kafka-ec2.sh <ip_publica> <ruta_pem> [--local]}
PEM=${2:?falta la ruta del .pem (con --local poner -)}
LOCAL=${3:-}
TAG=banco-xyz-eft          # tag Project que le pone provision.yml
G=https://localhost:8443
SALIDA=evidencias/kafka-ec2.txt
# la prueba local queda en otro archivo, para no confundirla con la evidencia de verdad
[ "$LOCAL" = "--local" ] && SALIDA=evidencias/kafka-ec2-simulado-local.txt
if docker compose version >/dev/null 2>&1; then DC="docker compose"; else DC="docker-compose"; fi

titulo() { echo; echo "== $* =="; }
# corre un comando en la ec2 por ssh. con --local lo corre aca mismo (el kafka simulado tambien se llama "kafka")
en_ec2() {
  echo "\$ ssh -i $PEM ec2-user@$IP '$1'"
  if [ "$LOCAL" = "--local" ]; then bash -c "$1"; else ssh -i "$PEM" -o StrictHostKeyChecking=accept-new ec2-user@"$IP" "$1"; fi
}

mkdir -p evidencias
{
echo "# $(date '+%Y-%m-%d %H:%M:%S') - kafka en ec2 ($IP) usado por los microservicios locales"
[ "$LOCAL" = "--local" ] && echo "# MODO --local: sin aws ni ssh, kafka simulado en un contenedor local (solo para probar el script)"

titulo "1. la instancia en aws"
if [ "$LOCAL" = "--local" ]; then
  echo "(--local: me salto aws ec2 describe-instances)"
else
  echo "\$ aws ec2 describe-instances --filters Name=tag:Project,Values=$TAG Name=instance-state-name,Values=running"
  aws ec2 describe-instances --filters "Name=tag:Project,Values=$TAG" "Name=instance-state-name,Values=running" \
    --query 'Reservations[].Instances[].{Id:InstanceId,Tipo:InstanceType,Estado:State.Name,IP:PublicIpAddress,DNS:PublicDnsName,Zona:Placement.AvailabilityZone}' \
    --output table
fi

titulo "2. dentro de la ec2 por ssh: hostname, ip publica, contenedores y topicos"
if [ "$LOCAL" = "--local" ]; then
  en_ec2 'hostname; docker ps --filter name=kafka --format "table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}"'
else
  # imdsv2: primero un token y despues la ip publica
  en_ec2 'hostname; T=$(curl -s -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60"); echo "ip publica: $(curl -s -H "X-aws-ec2-metadata-token: $T" http://169.254.169.254/latest/meta-data/public-ipv4)"; echo "dns publico: $(curl -s -H "X-aws-ec2-metadata-token: $T" http://169.254.169.254/latest/meta-data/public-hostname)"; cd /opt/kafka-banco && docker compose ps'
fi
echo
en_ec2 'docker exec kafka /opt/kafka/bin/kafka-topics.sh --describe --exclude-internal --bootstrap-server localhost:9092'

titulo "3. el bootstrap que usan ms-pagos (productor) y notificaciones (consumidor) aca en local"
for s in ms-pagos notificaciones; do
  echo "\$ $DC exec $s env | grep KAFKA"
  $DC exec -T $s env | grep KAFKA
  echo "\$ $DC logs $s | grep bootstrap.servers"
  $DC logs $s 2>/dev/null | grep -m1 -o "bootstrap.servers = \[[^]]*\]"
done
echo "\$ $DC ps   (sin el kafka local)"
$DC ps --format 'table {{.Name}}\t{{.Status}}'

titulo "4. una transferencia por el gateway (https) con token"
WEB=$(scripts/token.sh web)
echo '$ curl -sk -H "Authorization: Bearer $WEB" -H "Idempotency-Key: ec2-..." -d {"fromAccount":101,"toAccount":102,"amount":4321} '$G'/api/web/transferencias'
RESP=$(curl -sk -H "Authorization: Bearer $WEB" -H "Idempotency-Key: ec2-$(date +%s)" -H 'Content-Type: application/json' \
       -d '{"fromAccount":101,"toAccount":102,"amount":4321}' $G/api/web/transferencias)
echo "$RESP"
OP=$(echo "$RESP" | sed -n 's/.*"operationId":"\([^"]*\)".*/\1/p')
echo "operationId=$OP"
# el outbox manda cada 5s
sleep 8

titulo "5. el evento leido en la ec2 (consumidor de consola, grupo aparte)"
en_ec2 "docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic banco.transacciones --from-beginning --max-messages 200 --timeout-ms 10000 --property print.partition=true --property print.key=true 2>/dev/null | grep '$OP'"

titulo "6. notificaciones (local) lo consumio desde la ec2"
OPS=$(scripts/token.sh ops)
echo "\$ curl -sk -H \"Authorization: Bearer \$OPS\" $G/api/notificaciones | grep $OP"
curl -sk -H "Authorization: Bearer $OPS" $G/api/notificaciones | python3 -c '
import json, sys
op = sys.argv[1]
for n in json.load(sys.stdin):
    if n["eventoId"] == op:
        print(json.dumps(n, ensure_ascii=False))' "$OP"
echo
en_ec2 'docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group notificaciones'
} 2>&1 | tee "$SALIDA"
echo
echo "listo: $SALIDA"
