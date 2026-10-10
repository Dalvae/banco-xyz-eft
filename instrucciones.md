# instrucciones: como levantar y probar cada parte

todo lo de aca lo corri en mi mac con colima (4 cpu, 8gb) y `docker-compose` 5.5. lo que muestra cada paso esta en [evidencias/](evidencias/). en linux el comando suele ser `docker compose` (con espacio), funciona igual.

## 0. requisitos

- docker con compose (colima, docker desktop o docker en linux)
- curl y openssl (los usa `scripts/token.sh`)
- python3 solo para formatear json en algunos comandos (opcional)
- java 21 y maven solo si quieres correr los tests o algun servicio fuera de docker

## 1. levantar todo

```bash
cp .env.example .env          # y cambiar las claves si quieres
docker-compose --parallel 1 build
docker-compose up -d
docker-compose ps             # esperar que todos digan (healthy), menos kafka-ui que no tiene healthcheck
```

- el `build` va con `--parallel 1` a proposito: la etapa de maven es igual en todos los Dockerfile, asi la primera imagen baja las dependencias y compila, y las otras 11 usan el cache. si se construyen en paralelo se compila 12 veces a la vez y no alcanza la ram. la primera vez se demora (a mi me tomo ~20 min por la descarga de dependencias dentro de colima), despues ~1 min.
- los servicios parten en orden por los `depends_on` con healthcheck: mysql y kafka -> config-server y eureka -> auth-server -> ms-clientes -> ms-cuentas, ms-pagos, bff, notificaciones -> api-gateway.
- para apagar: `docker-compose down` (con `-v` borra tambien las bases y kafka).
- kafka y kafka-ui estan en el profile `kafka-local`, que el `.env.example` deja prendido (`COMPOSE_PROFILES=kafka-local`). para usar el kafka de la ec2 ver la seccion 11.

paginas para mirar en el navegador:

| que | url |
|---|---|
| eureka | http://localhost:8761 |
| kafka-ui | http://localhost:8090 |
| config-server | http://localhost:8888/ms-pagos/default (usuario `config`, clave `CONFIG_PASSWORD` del .env) |
| rutas del gateway | https://localhost:8443/actuator/gateway/routes |
| metadata oauth2 | https://localhost:8443/.well-known/openid-configuration |

## 2. tokens (oauth2)

el auth-server no tiene puerto propio, todo pasa por el gateway en https. el certificado es autofirmado, por eso los curl van con `-k`.

```bash
G=https://localhost:8443
WEB=$(scripts/token.sh web)        # authorization code + PKCE con el usuario demo, scopes web:read web:write
MOB=$(scripts/token.sh mobile)     # mobile:read
ATM=$(scripts/token.sh atm)        # atm:balance atm:withdraw atm:deposit
ATM_OTRO=$(scripts/token.sh atm other)   # mismo canal pero el usuario other (cuenta 102)
OPS=$(scripts/token.sh ops)        # client_credentials, para ver las notificaciones

# ver que trae el token (sub, aud, scope)
echo $ATM | cut -d. -f2 | tr '_-' '/+' | base64 -d
```

el script hace lo mismo que un navegador: `/oauth2/authorize` -> login -> code -> `/oauth2/token`. los tokens duran 5 minutos, si algo da 401 de repente hay que sacarlos de nuevo.

## 3. los 3 bff por el gateway

```bash
curl -sk -H "Authorization: Bearer $WEB" $G/api/web/me/dashboard        # perfil + cuentas + movimientos
curl -sk -H "Authorization: Bearer $WEB" "$G/api/web/cuentas/101/movimientos?page=0&size=5"
curl -sk -H "Authorization: Bearer $MOB" $G/api/mobile/me/inicio         # lo minimo para la app
curl -sk -H "Authorization: Bearer $ATM" $G/api/atm/cuentas/101/saldo    # solo numero enmascarado y saldo
```

seguridad por canal (lo esperado esta al lado):

```bash
curl -sk -o /dev/null -w "%{http_code}\n" $G/api/atm/cuentas/101/saldo                                   # 401 sin token
curl -sk -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $MOB" $G/api/atm/cuentas/101/saldo   # 401 token de otro canal (audiencia)
curl -sk -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $ATM" $G/api/web/me/dashboard        # 401
curl -sk -H "Authorization: Bearer $ATM_OTRO" $G/api/atm/cuentas/101/saldo                                # 403 la cuenta no es de other
```

## 4. operaciones (ms-pagos -> ms-cuentas) y eventos kafka

todas las operaciones llevan `Idempotency-Key`: si se manda 2 veces la misma, devuelve el mismo comprobante y no cobra 2 veces.

```bash
K=$(date +%s)
# cajero: retiro y deposito
curl -sk -H "Authorization: Bearer $ATM" -H "Idempotency-Key: ret-$K" -H 'Content-Type: application/json' \
     -d '{"amount":15000}' $G/api/atm/cuentas/101/retiros
curl -sk -H "Authorization: Bearer $ATM" -H "Idempotency-Key: dep-$K" -H 'Content-Type: application/json' \
     -d '{"amount":5000}' $G/api/atm/cuentas/101/depositos

# web: transferencia, transferencia grande (genera alerta MONTO_ALTO) y pago de servicio
curl -sk -H "Authorization: Bearer $WEB" -H "Idempotency-Key: tr-$K" -H 'Content-Type: application/json' \
     -d '{"fromAccount":101,"toAccount":102,"amount":20000}' $G/api/web/transferencias
curl -sk -H "Authorization: Bearer $WEB" -H "Idempotency-Key: tr-alta-$K" -H 'Content-Type: application/json' \
     -d '{"fromAccount":101,"toAccount":102,"amount":160000}' $G/api/web/transferencias
curl -sk -H "Authorization: Bearer $WEB" -H "Idempotency-Key: pago-$K" -H 'Content-Type: application/json' \
     -d '{"fromAccount":101,"biller":"ENEL","amount":12990}' $G/api/web/pagos

# transferencia a una cuenta que no existe: 409, y la saga devuelve la plata a la 101
curl -sk -H "Authorization: Bearer $WEB" -H "Idempotency-Key: tr-mala-$K" -H 'Content-Type: application/json' \
     -d '{"fromAccount":101,"toAccount":999,"amount":1000}' $G/api/web/transferencias

curl -sk -H "Authorization: Bearer $WEB" $G/api/web/me/operaciones    # historial (sale de ms-pagos)
```

gestion de cuentas y clientes desde la web:

```bash
curl -sk -H "Authorization: Bearer $WEB" -H 'Content-Type: application/json' \
     -d '{"displayName":"Cuenta ahorro","currency":"CLP"}' $G/api/web/cuentas        # abre (ms-cuentas valida el cliente en ms-clientes)
curl -sk -H "Authorization: Bearer $WEB" -X POST $G/api/web/cuentas/103/cierre       # cierra (solo con saldo 0), usar el id que devolvio
curl -sk -H "Authorization: Bearer $WEB" -X PUT -H 'Content-Type: application/json' \
     -d '{"phone":"+56933333333"}' $G/api/web/me/perfil                               # ms-clientes
```

lo que llego por kafka:

```bash
curl -sk -H "Authorization: Bearer $OPS" $G/api/notificaciones
docker-compose logs notificaciones | grep -E "Evento consumido|ALERTA"
docker-compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group notificaciones
```

y en kafka-ui (http://localhost:8090): topics -> `banco.transacciones` / `banco.alertas-seguridad` -> messages, y consumers -> `notificaciones` con lag 0.

pausar y reanudar el consumidor (el evento queda en kafka y llega al reanudar):

```bash
curl -sk -X POST -H "Authorization: Bearer $OPS" $G/api/notificaciones/listeners/transaccionesListener/pausar
curl -sk -X POST -H "Authorization: Bearer $OPS" $G/api/notificaciones/listeners/transaccionesListener/reanudar
```

## 5. resilience4j y fallback

config (en `config-repo/application.yml`): retry de 3 intentos cada 100 ms, circuito con ventana de 10, minimo 4 llamadas, se abre con 50 % de fallas y queda abierto 15s. la salida de estos mismos comandos esta en `evidencias/resiliencia.txt`.

```bash
# 1. todo sano, circuito CLOSED
for i in 1 2 3; do curl -sk -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $WEB" $G/api/web/me/dashboard; done
docker-compose exec bff-web wget -qO- http://localhost:8441/actuator/circuitbreakers

# 2. apago ms-cuentas
docker-compose stop ms-cuentas

# 3. las primeras llamadas se demoran (3 intentos cada una), despues el circuito se abre y responde altiro con "degraded": true
for i in 1 2 3 4 5; do curl -sk -w "  [%{http_code} %{time_total}s]\n" -H "Authorization: Bearer $WEB" $G/api/web/me/dashboard; done
docker-compose exec bff-web wget -qO- http://localhost:8441/actuator/retryevents/msCuentas   # RETRY 1, RETRY 2, ERROR 3
docker-compose exec bff-web wget -qO- http://localhost:8441/actuator/circuitbreakers         # msCuentas OPEN
curl -sk -w "  [%{http_code}]\n" -H "Authorization: Bearer $MOB" $G/api/mobile/me/inicio      # 200 degraded
curl -sk -w "  [%{http_code}]\n" -H "Authorization: Bearer $ATM" $G/api/atm/cuentas/101/saldo # 503, el cajero no inventa un saldo

# 4. ms-pagos tambien reintenta hacia ms-cuentas: la transferencia no se hace y no se cobra nada
curl -sk -w "  [%{http_code}]\n" -H "Authorization: Bearer $WEB" -H "Idempotency-Key: caido-$(date +%s)" -H "Content-Type: application/json" \
     -d '{"fromAccount":101,"toAccount":102,"amount":1000}' $G/api/web/transferencias
docker-compose exec ms-pagos wget -qO- http://localhost:8082/actuator/retryevents/msCuentas
docker-compose exec ms-pagos wget -qO- http://localhost:8082/actuator/circuitbreakers

# 5. vuelvo a levantar ms-cuentas: a los 15s pasa solo a HALF_OPEN y con 2 llamadas buenas vuelve a CLOSED
docker-compose start ms-cuentas
docker-compose exec bff-web wget -qO- http://localhost:8441/actuator/circuitbreakers         # HALF_OPEN
for i in 1 2; do curl -sk -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $WEB" $G/api/web/me/dashboard; done
docker-compose exec bff-web wget -qO- http://localhost:8441/actuator/circuitbreakers         # CLOSED

# 6. si se cae un bff entero, responde el fallback del gateway
docker-compose stop bff-atm
curl -sk -w "  [%{http_code}]\n" -H "Authorization: Bearer $ATM" $G/api/atm/cuentas/101/saldo   # 503 {"canal":"atm","degraded":true,...}
docker-compose start bff-atm
```

los endpoints `/actuator/circuitbreakers`, `/actuator/circuitbreakerevents`, `/actuator/retries` y `/actuator/retryevents` estan abiertos sin token (no muestran datos de clientes). como los bff y ms-pagos no tienen puerto hacia afuera se consultan con `docker-compose exec ... wget`.

## 6. escalado y eureka

```bash
docker-compose up -d --scale ms-cuentas=2
# en http://localhost:8761 aparece MS-CUENTAS con 2 instancias, o:
curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps/MS-CUENTAS | python3 -m json.tool | grep instanceId
DESDE=$(date -u +%Y-%m-%dT%H:%M:%SZ)     # para contar solo las llamadas de abajo
for i in $(seq 1 10); do curl -sk -H "Authorization: Bearer $ATM" $G/api/atm/cuentas/101/saldo; echo; done
docker-compose logs --since $DESDE ms-cuentas | grep "atendido por"    # las llamadas se reparten entre las 2
for c in $(docker-compose ps -q ms-cuentas); do echo "$(docker inspect -f {{.Name}} $c): $(docker logs --since $DESDE $c 2>&1 | grep -c "atendido por")"; done
```

lo mismo sirve para `ms-pagos`, `ms-clientes`, los bff y `notificaciones` (ninguno tiene `container_name` ni puerto publicado).

## 7. spring cloud config

```bash
source .env
curl -s -u config:$CONFIG_PASSWORD http://localhost:8888/ms-pagos/default | python3 -m json.tool
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8888/ms-pagos/default    # 401 sin clave
```

la carpeta `config-repo/` esta montada en el contenedor, si cambio un yml basta con reiniciar el servicio (`docker-compose restart ms-pagos`), no hay que reconstruir.

## 8. batch

los jobs no parten con el `up`, son contenedores que corren y terminan:

```bash
docker-compose run --rm batch                                          # transaccionesJob
docker-compose run --rm batch --spring.batch.job.name=interesesJob
docker-compose run --rm batch --spring.batch.job.name=estadoCuentaJob
docker-compose run --rm batch --spring.batch.job.name=interesesJob --batch.semana=semana_1   # otra semana
echo $?   # 0 si quedo COMPLETED, 1 si fallo
```

conteos y estado de las ejecuciones:

```bash
docker-compose exec mysql sh -c 'mysql -ubatch -p"$DB_PASSWORD" banco_xyz_batch -e "
  SELECT COUNT(*) FROM transacciones; SELECT COUNT(*) FROM intereses; SELECT COUNT(*) FROM estado_cuenta;
  SELECT job_execution_id, status, exit_code FROM BATCH_JOB_EXECUTION;"'
```

reejecucion automatica ante fallo critico (el primer chunk falla a proposito, el runner relanza la misma instancia y sigue desde la particion que fallo):

```bash
docker-compose run --rm batch --spring.batch.job.name=transaccionesJob --batch.demo.falla-critica=true
# en el log: "FAILED en un step, reejecuto en 2000 ms (intento 2 de 3)" y despues "termino con status=COMPLETED exit=0"
```

los reportes csv (omitidos, resumen diario, auditoria) quedan en el volumen `batch-reportes` (`/app/reportes`).

los resultados de cada job (con las lineas `Fin job=... read=... write=... descartadas=...` y `Tasa de omision`) quedaron en `evidencias/batch-*.txt`.

## 9. tests

```bash
mvn package     # 34 tests, no necesitan docker (h2 en modo mysql, kafka mockeado)
```

## 10. correr un servicio fuera de docker (desde el ide)

con el compose arriba se puede bajar un servicio y correrlo en el ide. por ejemplo ms-pagos:

```bash
docker-compose stop ms-pagos
CONFIG_PASSWORD=... DB_PASSWORD=... PAGOS_CLIENT_SECRET=... mvn -pl ms-pagos spring-boot:run
```

ojo que mysql y el auth-server no tienen puerto hacia el mac, para eso hay que agregarles `ports` en el compose. el gateway fuera de docker necesita el certificado: `scripts/certificado.sh`.

## 11. kafka en la nube (ec2)

en la entrega todo corre local y en aws (learner lab) va solo kafka, en una ec2 que arma el playbook de `infra/ansible/` (requisitos, puertos, alternativa con aws-cli y limpieza en [despliegue.md](despliegue.md)). al final `deploy.yml` imprime la ip. despues aca en local:

```bash
# en el .env: apagar el kafka local y apuntar a la ec2
#   COMPOSE_PROFILES=
#   KAFKA_BOOTSTRAP=<ip publica>:9094
docker-compose stop kafka kafka-ui     # si estaban arriba
docker-compose up -d                   # recrea los servicios con el nuevo KAFKA_BOOTSTRAP
docker-compose exec ms-pagos env | grep KAFKA
```

ms-pagos crea los topicos si no existen (el playbook tambien los crea). para volver al kafka local: `COMPOSE_PROFILES=kafka-local`, borrar `KAFKA_BOOTSTRAP` y `docker-compose up -d`.

### evidencia kafka en ec2

con la ec2 arriba y el compose apuntando a ella:

```bash
# 1. la instancia (id, tipo, estado, ip y dns publico)
aws ec2 describe-instances --filters Name=tag:Project,Values=banco-xyz-eft Name=instance-state-name,Values=running \
  --query 'Reservations[].Instances[].{Id:InstanceId,Tipo:InstanceType,Estado:State.Name,IP:PublicIpAddress,DNS:PublicDnsName}' --output table

# 2. dentro de la ec2: contenedores y topicos
ssh -i ~/.ssh/labsuser.pem ec2-user@<ip> 'hostname; cd /opt/kafka-banco && docker compose ps'
ssh -i ~/.ssh/labsuser.pem ec2-user@<ip> 'docker exec kafka /opt/kafka/bin/kafka-topics.sh --describe --exclude-internal --bootstrap-server localhost:9092'

# 3. que ms-pagos y notificaciones (locales) apuntan a la ec2
docker compose exec ms-pagos env | grep KAFKA
docker compose logs notificaciones | grep -m1 "bootstrap.servers"

# 4. una transferencia por el gateway
WEB=$(scripts/token.sh web)
curl -sk -H "Authorization: Bearer $WEB" -H "Idempotency-Key: ec2-$(date +%s)" -H 'Content-Type: application/json' \
     -d '{"fromAccount":101,"toAccount":102,"amount":4321}' https://localhost:8443/api/web/transferencias

# 5. ese evento leido dentro de la ec2
ssh -i ~/.ssh/labsuser.pem ec2-user@<ip> 'docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic banco.transacciones --from-beginning --max-messages 50 --timeout-ms 10000 --property print.key=true'

# 6. notificaciones (local) lo consumio desde la ec2, y el grupo quedo con lag 0
OPS=$(scripts/token.sh ops)
curl -sk -H "Authorization: Bearer $OPS" https://localhost:8443/api/notificaciones
ssh -i ~/.ssh/labsuser.pem ec2-user@<ip> 'docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group notificaciones'
```

capturas que hay que sacar a mano para el informe:

- kafka-ui de la ec2 en `http://<ip>:8090`: topics -> `banco.transacciones` -> messages (con el evento de la transferencia) y consumers -> `notificaciones`
- la consola de aws (ec2 -> instances) con la instancia `banco-xyz-eft` en running y su ip publica
- la terminal con la sesion ssh (`ssh -i ~/.ssh/labsuser.pem ec2-user@<ip>` y `docker compose ps`)
