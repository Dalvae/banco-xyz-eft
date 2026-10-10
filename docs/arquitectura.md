# Arquitectura Banco XYZ (EFT S9)

Diagramas del informe en mermaid (github los dibuja solo). Los png estan en `docs/diagramas/`, se generan con `mmdc -i archivo.mmd -o archivo.png -b white -s 2`.

Repo de la entrega: https://github.com/Dalvae/banco-xyz-eft

## Arquitectura general

Los canales entran por el api-gateway en https (8443), que enruta con lb:// al bff de cada canal. Los bff y los microservicios se registran en eureka y sacan su config del config-server. ms-pagos es el que mueve la plata: le pide a ms-cuentas el debito y el credito y publica los eventos en kafka. Cada servicio tiene su propia base.

```mermaid
%% arquitectura general del banco xyz (eft s9)
flowchart TB
    canales([Web / App movil / Cajero])
    gw[api-gateway :8443 HTTPS<br/>circuit breaker por ruta]
    auth[auth-server :9000<br/>OAuth2 / JWT]
    sc[eureka-server :8761<br/>config-server :8888]

    subgraph bffs[Backend for Frontend]
        direction LR
        bweb[bff-web :8441]
        bmob[bff-mobile :8442]
        batm[bff-atm :8444]
    end

    pagos[ms-pagos :8082<br/>saga + outbox]
    cuentas[ms-cuentas :8081<br/>dueno del saldo]
    clientes[ms-clientes :8083]

    mysql[(MySQL 8.4<br/>un schema por servicio)]
    kafka{{Kafka: banco.transacciones<br/>banco.alertas-seguridad}}
    notif[notificaciones :8450]
    batch[batch: 3 jobs]

    canales --> gw
    gw -- /oauth2, /login --> auth
    gw -. registro y config .- sc
    gw -- lb:// --> bweb & bmob & batm
    bweb & bmob & batm -- Resilience4j --> cuentas
    bweb & batm --> pagos
    bweb --> clientes
    pagos -- client_credentials --> cuentas
    cuentas --> clientes
    pagos -- outbox --> kafka --> notif
    cuentas & clientes & pagos & notif & batch --> mysql
```

## Despliegue: Docker Compose + Kafka en AWS

Los servicios corren con docker compose en una red de docker. Solo el gateway (y los paneles) publican puerto; los microservicios no tienen container_name ni puerto en el host para poder hacer `docker compose up --scale ms-cuentas=2`. En aws va solo kafka: 1 broker kraft (apache/kafka:3.9.1) en una ec2 t3.medium de 20 GB levantada con ansible (`infra/ansible`), con el listener externo 9094 y kafka-ui en 8090; el security group abre 22, 9094 y 8090 solo a mi ip. deploy.yml crea los topicos (rf 1) e imprime el KAFKA_BOOTSTRAP. Si la ec2 se reinicia cambia la ip y hay que correr provision.yml y deploy.yml de nuevo. Los servicios apuntan ahi con `KAFKA_BOOTSTRAP`. No hay balanceador de aws, el balanceo es el del gateway + loadbalancer + eureka (ver `despliegue.md`).

```mermaid
%% despliegue: servicios en docker compose en el equipo del alumno, kafka en una ec2 de aws (ansible)
flowchart TB
    usuario([Cliente]) --> gw

    subgraph local[Equipo del alumno: docker compose, red banco-net]
        gw[api-gateway :8443 HTTPS<br/>unico puerto de entrada]
        infra[eureka, config-server<br/>y auth-server]
        bff[bff-web, bff-mobile<br/>y bff-atm]
        c1[ms-cuentas #1]
        c2[ms-cuentas #2]
        p1[ms-pagos]
        cl1[ms-clientes]
        notif[notificaciones]
        db[(MySQL 8.4<br/>un schema por servicio)]
    end

    subgraph aws[AWS us-east-1: EC2 t3.medium]
        direction LR
        sg[Security Group<br/>22, 9094 y 8090<br/>solo IP del alumno] --> kafka[Kafka 3.9.1 KRaft<br/>1 broker :9094] --- kui[kafka-ui :8090]
    end
    dev([Alumno con ansible-playbook]) -- provision / deploy --> sg

    gw --> bff
    gw -.- infra
    bff -- "lb:// (--scale 2)" --> c1 & c2
    bff --> p1 & cl1
    p1 --> c1
    c1 & c2 & cl1 & notif --- db
    p1 -- KAFKA_BOOTSTRAP --> sg
    kafka -- consume --> notif
```

## Casos de uso por canal

Cada canal tiene su bff y sus scopes: la web lee y escribe, el movil solo lee un resumen y el cajero solo consulta saldo, retira y deposita.

```mermaid
%% casos de uso por canal (cada canal tiene su bff y sus scopes)
flowchart TB
    cw([Cliente web])
    cm([Cliente movil])
    ca([Cliente en cajero])
    ops([Operaciones])
    sched([Planificador batch])

    subgraph sweb[bff-web: web:read y web:write]
        uc1(Ver dashboard<br/>y movimientos)
        uc2(Transferir y<br/>pagar servicios)
        uc4(Abrir / cerrar cuenta<br/>y editar perfil)
    end
    subgraph smob[bff-mobile: mobile:read]
        uc6(Ver inicio<br/>resumido)
    end
    subgraph satm[bff-atm: atm:balance, withdraw, deposit]
        uc7(Consultar saldo)
        uc8(Retirar)
        uc9(Depositar)
    end
    subgraph snot[notificaciones: client_credentials]
        uc10(Ver notificaciones<br/>y alertas)
        uc11(Pausar / reanudar<br/>consumidor)
    end
    subgraph sbatch[batch: Spring Batch]
        uc12(Transacciones<br/>diarias)
        uc13(Intereses)
        uc14(Estados de<br/>cuenta anuales)
    end

    cw --> uc1 & uc2 & uc4
    cm --> uc6
    ca --> uc7 & uc8 & uc9
    ops --> uc10 & uc11
    sched --> uc12 & uc13 & uc14

    %% 3 filas para que no quede tan ancho
    uc1 ~~~ ca
    uc6 ~~~ ops
    uc8 ~~~ sched
```

## Transferencia: saga con compensacion y evento Kafka

No hay transaccion distribuida. ms-pagos guarda la operacion como PENDIENTE, debita, acredita y si el credito falla pide la reversa del debito. El evento se guarda en la tabla outbox en la misma transaccion que el estado COMPLETADA y otro proceso lo manda a kafka cada 5 s.

```mermaid
%% transferencia: saga con compensacion + evento kafka via outbox
sequenceDiagram
    autonumber
    actor U as Cliente web
    participant W as gateway + bff-web
    participant P as ms-pagos
    participant C as ms-cuentas
    participant K as Kafka y notificaciones

    U->>W: POST transferencia<br/>JWT + Idempotency-Key
    W->>P: POST /pagos (solo CB)
    Note over P: operacion PENDIENTE
    P->>C: debito origen (op-deb)
    C-->>P: 200 saldo
    P->>C: credito destino (op-cre)
    alt credito OK
        C-->>P: 200
        Note over P: COMPLETADA + evento<br/>en outbox (misma tx)
        P-->>U: 200 comprobante
    else rechazo o ms-cuentas caido
        C--xP: 4xx / timeout
        P->>C: reversa de op-deb
        Note over P: REVERTIDA o queda<br/>para el reconciliador
        P-->>U: 409 / 503 sin cargo
    end
    P->>K: outbox cada 5 s a banco.transacciones
    Note over K: guarda sin duplicar<br/>(evento_id) y ack
```

## OAuth2 por canal

Un cliente por canal (bank-web, bank-mobile, bank-atm) con authorization code + pkce. Entre servicios ms-pagos usa su propio token client_credentials con el scope cuentas:operar.

```mermaid
%% flujo oauth2 por canal: authorization code + pkce para usuarios, client_credentials entre servicios
sequenceDiagram
    autonumber
    participant App as App del canal
    participant A as auth-server<br/>(por el gateway)
    participant B as gateway + BFF
    participant P as ms-pagos
    participant C as ms-cuentas

    App->>A: /oauth2/authorize<br/>client_id del canal + PKCE
    A-->>App: login y redirect con code
    App->>A: /oauth2/token (code + verifier)
    A-->>App: JWT: aud = canal, scopes del canal
    App->>B: HTTPS con Bearer JWT
    B->>B: valida firma (JWKS), issuer,<br/>audiencia y scope
    B->>P: reenvia el token del usuario
    P->>P: @PreAuthorize por scope
    P->>A: client_credentials (ms-pagos)
    A-->>P: JWT scope cuentas:operar
    P->>C: /internal/... con token de servicio
```

## Jobs de Spring Batch

Los 3 jobs particionan el csv por rangos de lineas y procesan las particiones en paralelo. Las filas malas se saltan y quedan en la tabla omitidos, los errores transitorios de bd se reintentan 3 veces y si se pasa el umbral de omision el job termina FAILED.

```mermaid
%% los 3 jobs batch: particionado, reader/processor/writer, skip/retry, umbral y reejecucion
flowchart TB
    run([docker compose run batch<br/>job: transacciones, intereses<br/>o estadoCuenta]) --> part[CsvPartitioner<br/>rangos de lineas<br/>en paralelo]
    part --> r[Reader<br/>FlatFileItemReader]
    r --> pr[Processor<br/>normaliza y valida]
    pr --> w[Writer<br/>JdbcBatchItemWriter<br/>MySQL, chunk 100]
    r -. fila ilegible .-> omit[(tabla omitidos<br/>+ CSV)]
    pr -. dato invalido .-> omit
    w -. error transitorio de BD .-> retry[retry x3<br/>backoff exponencial]
    retry -.-> w
    w --> fin{tasa de omision<br/>vs umbral}
    fin -- bajo umbral --> ok([COMPLETED, exit 0])
    fin -- sobre umbral --> failu([FAILED, no se reintenta])
    w -- step FAILED<br/>por error critico --> re[ReejecucionRunner<br/>hasta 3 intentos]
    re -- restart de la misma instancia:<br/>solo la particion que fallo --> part
```

## Circuit breaker: estados

Config de config-repo/application.yml: ventana 10, minimo 4 llamadas, 50 % de fallas, 15 s abierto y 2 llamadas en half-open. Los 4xx no cuentan como falla.

```mermaid
%% estados del circuit breaker (config-repo/application.yml, config default para msCuentas, msPagos y msClientes)
stateDiagram-v2
    direction TB
    [*] --> CLOSED
    CLOSED --> OPEN: 50 % de fallas en las ultimas 10 llamadas (minimo 4)
    OPEN --> HALF_OPEN: pasan 15 s (transicion automatica)
    HALF_OPEN --> CLOSED: las 2 llamadas de prueba salen bien
    HALF_OPEN --> OPEN: falla una llamada de prueba
    note right of CLOSED
        los 4xx (ResponseStatusException) no cuentan como falla
    end note
    note right of OPEN
        no llama al servicio, va directo al fallback
    end note
```

## Circuit breaker + retry

El circuit breaker va afuera y el retry adentro (aspect-order 1 y 2): 3 intentos cada 100 ms y despues el fallback. Se usa de los bff a los ms y tambien entre ms (ms-pagos -> ms-cuentas, ms-cuentas -> ms-clientes). Los POST de plata desde los bff solo llevan circuit breaker para no duplicar un cobro.

```mermaid
%% circuit breaker afuera (aspect-order 1) y retry adentro (aspect-order 2): ejemplo bff-mobile -> ms-cuentas caido
sequenceDiagram
    autonumber
    participant B as bff-mobile
    participant CB as CircuitBreaker msCuentas
    participant R as Retry msCuentas
    participant C as ms-cuentas
    B->>CB: home() para GET /api/mobile/me/inicio
    CB->>R: circuito CLOSED, deja pasar
    R->>C: intento 1
    C--xR: ResourceAccessException
    Note over R: espera 100 ms
    R->>C: intento 2
    C--xR: ResourceAccessException
    Note over R: espera 100 ms
    R->>C: intento 3
    C--xR: ResourceAccessException
    R-->>CB: se agotan los 3 intentos, cuenta 1 falla
    CB-->>B: homeFallback: inicio vacio, degraded=true, "Servicio temporalmente no disponible"
    Note over B,CB: los POST de plata desde los BFF (retiro, deposito, transferencia, pago)<br/>solo tienen circuit breaker: sin retry para no duplicar un cobro
```

## Legacy vs nuevo

Resumen de lo que cambia con la migracion.

```mermaid
%% antes y despues
flowchart LR
    subgraph legacy[Legacy]
        direction TB
        f1[Web] & f2[Movil] & f3[Cajero] --> mono[Backend monolitico<br/>misma respuesta a todos]
        mono --> mf[(BD unica)]
        cron[cron + scripts Shell<br/>COBOL en mainframe] --> mf
    end

    subgraph nuevo[Nuevo]
        direction TB
        n1[Web] & n2[Movil] & n3[Cajero] --> ngw[api-gateway HTTPS]
        ngw --> nb[3 BFF por canal]
        nb --> nms[ms-cuentas / ms-pagos / ms-clientes<br/>escalables por separado]
        nms --> nd[(MySQL: un schema por servicio)]
        nms --> nk{{Kafka}} --> nn[notificaciones]
        nbatch[Spring Batch<br/>particionado, skip/retry] --> nbd[(MySQL: schema batch)]
    end

    legacy == migracion ==> nuevo
```
