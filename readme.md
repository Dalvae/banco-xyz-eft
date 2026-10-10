# banco xyz - semana 8

repo: https://github.com/Dalvae/banco-xyz-bff-s5 (lo de la semana 5 quedo en el tag `semana-5`)

sumativa 3 de backend III. sigo el proyecto de la semana 5 y 6 (bank-api + un bff por canal, con los datos de https://github.com/KariVillagran/bank_legacy_data) y le agregue lo que pide esta semana:

- auth-server oauth2 (spring authorization server)
- un Dockerfile por microservicio
- docker-compose.yaml que levanta todo
- resilience4j (circuit breaker + retry con fallback) en los bff
- kafka: bank-api produce un evento por cada retiro y notificaciones lo consume

## objetivo

preparar los microservicios del banco para la nube: que sean seguros (oauth2), que aguanten si un servicio se cae (resilience4j), que se avisen cosas por eventos (kafka) y que todo se levante con un solo `docker-compose up`.

## propuesta tecnica

la idea es que el banco funcione como microservicios que se pueden levantar en cualquier lado con docker:

- en s5 el token lo daba bank-api con un endpoint hecho a mano. ahora hay un auth-server aparte y todos los demas servicios solo validan el token (resource servers).
- los bff llaman a bank-api por http, si bank-api se cae el circuit breaker corta las llamadas y el bff responde un fallback en vez de quedarse colgado.
- el retiro del cajero publica un evento en kafka, asi otro servicio (notificaciones) se entera sin que bank-api tenga que llamarlo. despues se podrian agregar otros consumidores (antifraude por ej) sin tocar bank-api.
- elegi kafka y no jms porque es lo que usaba la guia y la semana 7.

```
                   +-------------+
  usuario/curl --> | auth-server | --(jwks)--> todos validan el token
                   +-------------+
  web    --> bff-web    -+
  movil  --> bff-mobile -+--(resilience4j)--> bank-api --(RetiroRealizado)--> kafka --> notificaciones
  cajero --> bff-atm    -+                                banco.movimientos
```

| servicio | puerto | que hace |
|---|---|---|
| auth-server | 9000 | emite los tokens (authorization code + PKCE y client credentials) |
| bank-api | 8440 (solo red interna) | cuentas, movimientos, retiros. **productor kafka** |
| bff-web | 8441 | dashboard web |
| bff-mobile | 8442 | inicio de la app |
| bff-atm | 8443 | saldo y retiro del cajero |
| notificaciones | 8450 | **consumidor kafka**, guarda un aviso por cada retiro |
| kafka-1, kafka-2, kafka-3 | 9092 adentro, 19092/29092/39092 hacia el mac | cluster de 3 brokers en modo kraft |
| kafka-ui | 8090 | pagina para ver brokers, topicos y consumidores |

## estructura

```
shared/          cosas comunes: cliente http a bank-api, config de seguridad, el evento de kafka
auth-server/     spring authorization server (clientes y usuarios)
bank-api/        dominio + importador csv + productor kafka (eventos/MovimientosProducer)
bff-web/ bff-mobile/ bff-atm/   un bff por canal, el circuit breaker esta en los service
notificaciones/  consumidor kafka + endpoint para ver lo que llego
*/Dockerfile     uno por microservicio
docker-compose.yaml
scripts/token.sh saca un token con curl
evidencias/      capturas
```

## como ejecutar

necesitas docker con compose (yo use colima en mac, con `docker-compose`) y curl + openssl para el script del token. java no hace falta porque las imagenes compilan adentro.

```bash
cp .env.example .env
docker-compose up -d --build
docker-compose ps     # esperar que los 3 kafka, auth-server y bank-api digan healthy (como 1 min)
```

para apagar `docker-compose down` (con `-v` borra las bases tambien).

los tests corren con `mvn verify` si tienes java 21 y maven.

## probar

```bash
# tokens del usuario demo, por authorization code + PKCE
WEB=$(scripts/token.sh web)
MOB=$(scripts/token.sh mobile)
ATM=$(scripts/token.sh atm)

curl -H "Authorization: Bearer $WEB" http://localhost:8441/api/web/me/dashboard
curl -H "Authorization: Bearer $MOB" http://localhost:8442/api/mobile/me/inicio
curl -H "Authorization: Bearer $ATM" http://localhost:8443/api/atm/cuentas/101/saldo
curl -H "Authorization: Bearer $ATM" -H "Idempotency-Key: retiro-1" -H 'Content-Type: application/json' \
     -d '{"amount":15000}' http://localhost:8443/api/atm/cuentas/101/retiros

# token de servicio (client credentials) para ver las notificaciones que llegaron por kafka
OPS=$(scripts/token.sh ops)
curl -H "Authorization: Bearer $OPS" http://localhost:8450/api/notificaciones
```

## por criterio

### oauth2

- `auth-server` con spring authorization server. clientes:
  - `bank-web`, `bank-mobile`, `bank-atm`: authorization code con PKCE (son publicos, no guardan secreto)
  - `ops-notificaciones`: client credentials (servicio, sin usuario)
- usuarios `demo` (cuenta 101) y `other` (cuenta 102), la clave va en `.env`
- bank-api, los bff y notificaciones son resource servers: validan firma con `/oauth2/jwks`, el issuer y la audiencia (cada bff solo acepta tokens de su cliente). cada endpoint pide su scope con `@PreAuthorize` y bank-api revisa que la cuenta sea del usuario.
- metadata en http://localhost:9000/.well-known/openid-configuration
- sin token da 401, token de otro canal 401 (audiencia), cuenta ajena 403

### dockerfile

uno por servicio (`auth-server/`, `bank-api/`, `bff-web/`, `bff-mobile/`, `bff-atm/`, `notificaciones/`). multi-stage: con `maven:3.9-eclipse-temurin-21` compilo y la imagen final es `eclipse-temurin:21-jre-alpine` con el jar no mas. el contexto es la raiz porque todos necesitan el pom padre.

### docker-compose

- 10 contenedores: 3 brokers kafka, kafka-ui, auth-server, bank-api, 3 bff y notificaciones, en la red `banco-net`
- se llaman por nombre (`http://bank-api:8440`, `kafka-1:9092,kafka-2:9092,kafka-3:9092`)
- healthcheck en los kafka, auth-server y bank-api, y `depends_on` con `service_healthy` para que partan en orden
- `restart: unless-stopped`, volumenes para las bases h2, claves en `.env` (hay un `.env.example`)
- adentro de la red todo va en http, en s5 tenia https con certificados locales pero en docker se complicaba mucho

### resilience4j

- en los service de cada bff: `@CircuitBreaker` + `@Retry` con la instancia `bankApi` y un fallback
- circuit breaker: ventana de 10 llamadas, se abre con 50% de fallas (minimo 4), espera 15s y pasa a half-open solo
- retry: 3 intentos cada 500ms, solo si bank-api no responde o da 5xx. el retiro del cajero no se reintenta a proposito (es plata)
- fallback: web y movil responden 200 con `"degraded": true`, el cajero responde 503
- estado en `/actuator/circuitbreakers` y `/actuator/circuitbreakerevents`

```bash
curl http://localhost:8441/actuator/circuitbreakers     # CLOSED
docker-compose stop bank-api
for i in 1 2 3 4 5; do curl -s -H "Authorization: Bearer $WEB" http://localhost:8441/api/web/me/dashboard; echo; done
curl http://localhost:8441/actuator/circuitbreakers     # OPEN
docker-compose start bank-api
# a los 15s pasa a HALF_OPEN y con 2 llamadas buenas vuelve a CLOSED
```

### kafka

- productor: bank-api (`MovimientosProducer`). cada retiro manda un `RetiroRealizado` al topico `banco.movimientos` (key = id de la cuenta)
- si kafka esta caido el retiro no se pierde ni el evento tampoco (outbox): el retiro guarda el evento en la tabla `evento_pendiente` en la misma transaccion, y cada 5s `MovimientosProducer` manda los que faltan. si kafka no responde quedan ahi y se reintentan solos cuando vuelve
- como un evento puede llegar 2 veces (se reintenta), notificaciones tiene `operation_id` unico y los repetidos los salta
- consumidor: notificaciones (`MovimientosListener`, grupo `notificaciones`), guarda la notificacion en su h2
- la config esta armada como en la clase de la semana 8:
  - `bank-api/.../eventos/KafkaProducerConfig`: `ProducerFactory` con `StringSerializer` para la key y `JsonSerializer` para el evento, el `KafkaTemplate` y el `NewTopic` (lo crea el `KafkaAdmin` de spring boot)
  - `notificaciones/.../KafkaConsumerConfig`: `ConsumerFactory` con `JsonDeserializer` y `ConcurrentKafkaListenerContainerFactory` con ack manual
  - `notificaciones/.../KafkaListenerController`: pausar / reanudar / ver estado del listener con el `KafkaListenerEndpointRegistry`, pide el scope `notificaciones:admin`

para ver que funciona entro a kafka-ui en http://localhost:8090, ahi se ven los brokers, el topico con sus particiones, los mensajes que van llegando y el lag del consumidor

como en la clase deje un cluster de 3 brokers:

- `kafka-1`, `kafka-2` y `kafka-3`, los 3 son broker y controller (kraft, sin zookeeper). la config comun esta una vez arriba del compose (`x-kafka-comun`)
- `banco.movimientos` tiene 3 particiones y replication factor 3
- `min.insync.replicas=2` y el productor con `acks=all`, asi si se cae 1 broker se sigue escribiendo

si el pc no da para levantar todo, se puede subir solo kafka con `docker-compose up -d kafka-1 kafka-2 kafka-3 kafka-ui` y correr los microservicios desde el ide, ya vienen apuntando a `localhost:19092,localhost:29092,localhost:39092`

## evidencias

las capturas de consola son de mi terminal (kitty): a la izquierda `docker-compose up --attach bank-api --attach notificaciones` con los logs en vivo y a la derecha los comandos. las de kafka-ui son del navegador. estan en [evidencias/capturas](evidencias/capturas/).

### 1. dockerfiles e imagenes

un Dockerfile por microservicio (multi-stage, compila con maven y deja solo el jar) y las 6 imagenes construidas.

![dockerfiles](evidencias/capturas/01-docker-imagenes.png)

### 2. docker-compose arriba

los 10 contenedores corriendo, auth-server, bank-api y los kafka en healthy.

![docker compose ps](evidencias/capturas/02-docker-compose-ps.png)

### 3. auth-server

la configuracion de openid y las llaves publicas (jwks) que usan los otros servicios para validar el token.

![metadata oauth2](evidencias/capturas/03-oauth2-metadata-jwks.png)

### 4. login oauth2

al pedir un token con authorization code te manda al login del auth-server.

![login](evidencias/capturas/04-oauth2-login-authorization-code.png)

### 5. llamadas con y sin token

con su token cada bff responde 200. sin token da 401, con el token de otro canal tambien 401 y si pido una cuenta que no es mia da 403.

![llamadas 200 401 403](evidencias/capturas/06-oauth2-llamadas-200-401-403.png)

### 6. evento de kafka

hago un retiro en el cajero, bank-api publica el evento y notificaciones lo consume y lo guarda.

![productor y consumidor](evidencias/capturas/07-kafka-evento-productor-consumidor.png)

### 7. resilience4j: bank-api caido

apago bank-api. las primeras llamadas se demoran por los reintentos, despues el circuito se abre y el bff responde el fallback al tiro. el cajero da 503.

![circuito abierto](evidencias/capturas/09-resilience4j-circuito-abierto.png)

### 8. resilience4j: se recupera

vuelvo a levantar bank-api y el circuito pasa de OPEN a HALF_OPEN y despues a CLOSED.

![recuperacion](evidencias/capturas/10-resilience4j-recuperacion.png)

### 9. restart de docker

mato el proceso de bff-mobile y docker lo vuelve a levantar solo por el `restart: unless-stopped`.

![restart](evidencias/capturas/11-docker-restart-unless-stopped.png)

### 10. tests

`mvn verify` en verde.

![tests](evidencias/capturas/12-mvn-verify.png)

### 11. kafka-ui: brokers

los 3 brokers arriba y ninguna replica fuera de sync.

![brokers](evidencias/capturas/14a-kafka-ui-brokers.png)

### 12. kafka-ui: topico

el topico `banco.movimientos` con 3 particiones y 3 replicas.

![topico](evidencias/capturas/14b-kafka-ui-topico.png)

### 13. kafka-ui: mensajes

hice 3 retiros en el cajero y aca se ven los 3 eventos que llegaron al topico.

![mensajes](evidencias/capturas/14c-kafka-ui-mensajes.png)

### 14. kafka-ui: consumidor

el grupo `notificaciones` conectado y con lag 0, o sea ya leyo todos los mensajes.

![consumidor](evidencias/capturas/14d-kafka-ui-consumidor.png)

### 15. kafka caido

congelo los 3 kafka con `docker-compose pause` y hago un retiro. el retiro funciona igual y el evento queda pendiente. cuando vuelve kafka el evento sale solo y notificaciones lo recibe, no se pierde nada.

![kafka caido](evidencias/capturas/15-kafka-caido-outbox.png)

## cosas que faltaron

- la llave del auth-server se genera cada vez que parte, si se reinicia hay que pedir tokens de nuevo
- usuarios y clientes en memoria
