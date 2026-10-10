# despliegue en aws

esto no lo deje corriendo en aws (no tengo como pagarlo todo el semestre), pero el proyecto esta armado para que el paso sea directo: cada servicio es una imagen docker, la config sale del config-server, los servicios se encuentran por eureka y todo lo que cambia entre ambientes va por variables de entorno (`AUTH_URL`, `EUREKA_URL`, `MYSQL_HOST`, `KAFKA_BOOTSTRAP`, claves).

hay 2 caminos. el **a** es el mas simple (una ec2 con el mismo docker-compose) y el **b** es el que usaria en produccion (ecs fargate con servicios administrados).

region de ejemplo `us-east-1`, cuenta `123456789012` (cambiar por la de uno).

```bash
export AWS_REGION=us-east-1
export CUENTA=$(aws sts get-caller-identity --query Account --output text)
export ECR=$CUENTA.dkr.ecr.$AWS_REGION.amazonaws.com
```

## que va donde

| local (docker-compose) | aws |
|---|---|
| imagenes `banco-xyz/*` | **ecr**, un repositorio por servicio |
| contenedores | **ecs fargate** (un servicio ecs por microservicio) o una **ec2** con docker compose |
| api-gateway https con certificado autofirmado | **alb** con certificado de **acm** (el https termina en el alb, el gateway queda en http adentro de la vpc) |
| mysql con un schema por servicio | **rds mysql** (uno por servicio, o uno con usuarios separados como aca) |
| kafka 1 broker | **amazon msk** (3 brokers, uno por zona) o kafka en ec2 (playbook de `infra/ansible/`) |
| `.env` | **secrets manager** |
| `docker-compose logs` | **cloudwatch logs** (driver `awslogs`) |
| `--scale ms-cuentas=2` | **application auto scaling** del servicio ecs |
| eureka + config-server | igual, como servicios ecs (o cambiar a cloud map y parameter store, pero no hace falta) |

## 1. imagenes a ecr (sirve para los 2 caminos)

```bash
aws ecr get-login-password | docker login --username AWS --password-stdin $ECR

for s in config-server eureka-server api-gateway auth-server ms-cuentas ms-pagos ms-clientes \
         bff-web bff-mobile bff-atm notificaciones batch; do
  aws ecr create-repository --repository-name banco-xyz/$s --image-scanning-configuration scanOnPush=true || true
  # fargate y las t3 son x86, mi mac es arm: por eso --platform
  docker build --platform linux/amd64 -f $s/Dockerfile -t $ECR/banco-xyz/$s:1.0.0 .
  docker push $ECR/banco-xyz/$s:1.0.0
done
```

## 2. secretos en secrets manager

```bash
aws secretsmanager create-secret --name banco-xyz/prod --secret-string '{
  "DEMO_PASSWORD":"...", "OPS_CLIENT_SECRET":"...", "PAGOS_CLIENT_SECRET":"...",
  "CONFIG_PASSWORD":"...", "DB_PASSWORD":"..."}'
```

en ecs cada task definition los toma con `secrets` (`valueFrom: arn:...:secret:banco-xyz/prod:DB_PASSWORD::`), asi no quedan en texto plano en la task definition ni en el repo.

## camino a: una ec2 con docker compose

es lo mismo que corre en mi mac, sirve para una demo o un ambiente de pruebas.

```bash
# security group: 443 desde internet (alb) y 22 solo desde mi ip
aws ec2 create-security-group --group-name banco-xyz-sg --description "banco xyz eft"
aws ec2 authorize-security-group-ingress --group-name banco-xyz-sg --protocol tcp --port 22 --cidr $(curl -s ifconfig.me)/32
aws ec2 authorize-security-group-ingress --group-name banco-xyz-sg --protocol tcp --port 8443 --cidr 0.0.0.0/0

# t3.large (8gb) porque es lo que necesita todo junto, amazon linux 2023
AMI=$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 --query Parameter.Value --output text)
aws ec2 run-instances --image-id $AMI --instance-type t3.large --key-name mi-llave \
  --security-groups banco-xyz-sg --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=30}' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=banco-xyz}]'
```

y en la ec2:

```bash
sudo dnf install -y docker git && sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user   # salir y entrar de nuevo
sudo curl -L https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
git clone https://github.com/Dalvae/banco-xyz-eft && cd banco-xyz-eft
cp .env.example .env    # o sacar los valores de secrets manager con aws secretsmanager get-secret-value
docker-compose --parallel 1 build && docker-compose up -d
```

para tener https con un certificado de verdad se pone un alb delante (paso 5 del camino b, con el target group apuntando a la ec2 en el puerto 8443 y protocolo HTTPS).

### automatizado con ansible

en `infra/ansible/` deje un playbook que hace la parte de la ec2 sin clickear: `provision.yml` crea la ec2 y su security group (solo abre ssh y kafka para mi ip), `deploy.yml` instala docker y levanta kafka (1 broker kraft + kafka-ui) y `teardown.yml` borra todo. esta pensado para el learner lab de aws academy. el resto del sistema queda local apuntando a ese kafka:

```bash
cd infra/ansible
ansible-galaxy collection install -r requirements.yml
ansible-playbook provision.yml -e mi_ip=$(curl -s ifconfig.me)/32
ansible-playbook deploy.yml          # al final imprime KAFKA_BOOTSTRAP=<ip>:9094
cd ../..
echo "KAFKA_BOOTSTRAP=<ip>:9094" >> .env    # el compose lo usa en vez del kafka local
docker-compose up -d
# al terminar
cd infra/ansible && ansible-playbook teardown.yml
```

los pasos y variables estan en [infra/ansible/README.md](infra/ansible/README.md). este playbook no lo corri para la entrega, los comandos de aws-cli de arriba son la forma manual del mismo camino.

## camino b: ecs fargate + msk + rds + alb (produccion)

### 3. red, cluster y logs

uso la vpc por defecto para no alargar esto (en produccion iria una vpc con subredes privadas para los servicios y publicas solo para el alb).

```bash
VPC=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)
SUBNETS=$(aws ec2 describe-subnets --filters Name=vpc-id,Values=$VPC --query 'Subnets[].SubnetId' --output text | tr '\t' ',')

aws ecs create-cluster --cluster-name banco-xyz --capacity-providers FARGATE FARGATE_SPOT
aws logs create-log-group --log-group-name /ecs/banco-xyz
aws logs put-retention-policy --log-group-name /ecs/banco-xyz --retention-in-days 14

# service discovery: cada servicio queda como <nombre>.banco.local (lo usan config-server, eureka y auth-server)
aws servicediscovery create-private-dns-namespace --name banco.local --vpc $VPC
```

### 4. bases y kafka administrados

```bash
# rds mysql (una instancia chica, un schema y usuario por servicio con el mismo docker/mysql/init.sh)
aws rds create-db-instance --db-instance-identifier banco-xyz-mysql --engine mysql --engine-version 8.4 \
  --db-instance-class db.t4g.micro --allocated-storage 20 --master-username admin \
  --manage-master-user-password --no-publicly-accessible --backup-retention-period 7

# msk con 3 brokers (uno por zona). replication factor 3 y min.insync.replicas 2 como tenia en s8
aws kafka create-cluster-v2 --cluster-name banco-xyz-msk --provisioned '{
  "KafkaVersion":"3.8.x", "NumberOfBrokerNodes":3,
  "BrokerNodeGroupInfo":{"InstanceType":"kafka.t3.small","ClientSubnets":["subnet-a","subnet-b","subnet-c"]},
  "EncryptionInfo":{"EncryptionInTransit":{"ClientBroker":"TLS"}}}'
aws kafka get-bootstrap-brokers --cluster-arn <arn>     # -> KAFKA_BOOTSTRAP
```

en `config-repo/ms-pagos.yml` el numero de replicas de los topicos sale de `KAFKA_REPLICAS`, en msk se pone `KAFKA_REPLICAS=3` sin recompilar. para tls hay que agregar `spring.kafka.security.protocol=SSL` en `config-repo/application.yml`.

### 5. alb con https (acm)

```bash
# certificado de acm (se valida por dns en el dominio de uno)
aws acm request-certificate --domain-name api.bancoxyz.cl --validation-method DNS

aws elbv2 create-load-balancer --name banco-xyz-alb --type application --subnets ${SUBNETS//,/ } --security-groups <sg-alb>
aws elbv2 create-target-group --name banco-xyz-gateway --protocol HTTP --port 8443 --vpc-id $VPC \
  --target-type ip --health-check-path /actuator/health
aws elbv2 create-listener --load-balancer-arn <alb-arn> --protocol HTTPS --port 443 \
  --certificates CertificateArn=<acm-arn> --ssl-policy ELBSecurityPolicy-TLS13-1-2-2021-06 \
  --default-actions Type=forward,TargetGroupArn=<tg-arn>
# y el 80 redirige a 443
aws elbv2 create-listener --load-balancer-arn <alb-arn> --protocol HTTP --port 80 \
  --default-actions 'Type=redirect,RedirectConfig={Protocol=HTTPS,Port=443,StatusCode=HTTP_301}'
```

en aws el gateway corre con `SERVER_SSL_ENABLED=false` porque el https lo termina el alb. el alb manda los `X-Forwarded-*` y el gateway confia en la vpc (`trusted-proxies` en `config-repo/api-gateway.yml`, ya incluye 10.x y 172.x).

### 6. task definitions y servicios

una task definition por servicio. ejemplo de ms-cuentas (`ecs/ms-cuentas.json`):

```json
{
  "family": "ms-cuentas",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512", "memory": "1024",
  "executionRoleArn": "arn:aws:iam::123456789012:role/ecsTaskExecutionRole",
  "containerDefinitions": [{
    "name": "ms-cuentas",
    "image": "123456789012.dkr.ecr.us-east-1.amazonaws.com/banco-xyz/ms-cuentas:1.0.0",
    "portMappings": [{ "containerPort": 8081 }],
    "environment": [
      { "name": "JAVA_TOOL_OPTIONS", "value": "-XX:MaxRAMPercentage=75" },
      { "name": "CONFIG_SERVER_URL", "value": "http://config-server.banco.local:8888" },
      { "name": "EUREKA_URL", "value": "http://eureka-server.banco.local:8761/eureka" },
      { "name": "AUTH_URL", "value": "http://auth-server.banco.local:9000" },
      { "name": "AUTH_ISSUER", "value": "http://auth-server:9000" },
      { "name": "MYSQL_HOST", "value": "banco-xyz-mysql.xxxx.us-east-1.rds.amazonaws.com" }
    ],
    "secrets": [
      { "name": "DB_PASSWORD", "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789012:secret:banco-xyz/prod:DB_PASSWORD::" },
      { "name": "CONFIG_PASSWORD", "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789012:secret:banco-xyz/prod:CONFIG_PASSWORD::" }
    ],
    "healthCheck": { "command": ["CMD-SHELL", "wget -qO- http://localhost:8081/actuator/health | grep -q UP"], "interval": 15, "retries": 5, "startPeriod": 60 },
    "logConfiguration": { "logDriver": "awslogs", "options": {
      "awslogs-group": "/ecs/banco-xyz", "awslogs-region": "us-east-1", "awslogs-stream-prefix": "ms-cuentas" } }
  }]
}
```

```bash
aws ecs register-task-definition --cli-input-json file://ecs/ms-cuentas.json
aws ecs create-service --cluster banco-xyz --service-name ms-cuentas --task-definition ms-cuentas \
  --desired-count 2 --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[<sg-servicios>],assignPublicIp=ENABLED}" \
  --service-registries registryArn=<arn-de-cloud-map-ms-cuentas>
```

el api-gateway ademas lleva `--load-balancers targetGroupArn=<tg-arn>,containerName=api-gateway,containerPort=8443`. el orden de creacion es el mismo que en el compose: config-server, eureka-server, auth-server, ms-clientes, ms-cuentas, ms-pagos, bff, notificaciones, api-gateway.

### 7. autoescalado

```bash
aws application-autoscaling register-scalable-target --service-namespace ecs \
  --resource-id service/banco-xyz/ms-cuentas --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 --max-capacity 6

aws application-autoscaling put-scaling-policy --service-namespace ecs \
  --resource-id service/banco-xyz/ms-cuentas --scalable-dimension ecs:service:DesiredCount \
  --policy-name cpu-60 --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration '{
    "TargetValue": 60.0,
    "PredefinedMetricSpecification": {"PredefinedMetricType": "ECSServiceAverageCPUUtilization"},
    "ScaleOutCooldown": 60, "ScaleInCooldown": 120 }'
```

lo mismo para ms-pagos y los bff. las replicas nuevas se registran solas en eureka y el loadbalancer las empieza a usar (igual que con `--scale` en local).

### 8. batch con eventbridge

el batch no es un servicio, es una tarea que corre y termina. con eventbridge scheduler se corre cada noche:

```bash
aws scheduler create-schedule --name banco-xyz-transacciones --schedule-expression "cron(0 3 * * ? *)" \
  --flexible-time-window Mode=OFF --target '{
    "Arn": "arn:aws:ecs:us-east-1:123456789012:cluster/banco-xyz",
    "RoleArn": "arn:aws:iam::123456789012:role/eventbridge-ecs",
    "EcsParameters": {"TaskDefinitionArn": "arn:aws:ecs:us-east-1:123456789012:task-definition/batch",
      "LaunchType": "FARGATE", "NetworkConfiguration": {"awsvpcConfiguration": {"Subnets": ["subnet-a"], "AssignPublicIp": "ENABLED"}}},
    "Input": "{\"containerOverrides\":[{\"name\":\"batch\",\"command\":[\"--spring.batch.job.name=transaccionesJob\"]}]}",
    "RetryPolicy": {"MaximumRetryAttempts": 2}}'
```

el `RetryPolicy` es el equivalente al `restart: on-failure` del compose: si el contenedor sale con codigo 1, eventbridge lo vuelve a lanzar y `ReejecucionRunner` retoma la ejecucion FAILED desde donde quedo. los csv irian en s3 (`--ruta.archivo.transacciones=...` apuntando a un volumen o bajandolos al partir).

### 9. monitoreo

```bash
# alarma si el gateway responde 5xx (por ejemplo fallbacks porque se cayo un bff)
aws cloudwatch put-metric-alarm --alarm-name banco-xyz-5xx --namespace AWS/ApplicationELB \
  --metric-name HTTPCode_Target_5XX_Count --dimensions Name=LoadBalancer,Value=app/banco-xyz-alb/xxxx \
  --statistic Sum --period 60 --threshold 10 --comparison-operator GreaterThanThreshold --evaluation-periods 2 \
  --alarm-actions arn:aws:sns:us-east-1:123456789012:banco-xyz-alertas

# buscar una operacion por su traceId en los logs de todos los servicios
aws logs filter-log-events --log-group-name /ecs/banco-xyz --filter-pattern '"6acab6e24cca14003f0c2de2e0601690"'
```

y container insights en el cluster (`aws ecs update-cluster-settings --cluster banco-xyz --settings name=containerInsights,value=enabled`) para cpu y memoria por servicio.

## costos y limpieza

lo mas caro es msk y el alb. para una demo conviene el camino a (o el playbook de ansible) y borrar todo al terminar: `aws ecs delete-service --force`, `aws rds delete-db-instance --skip-final-snapshot`, `aws kafka delete-cluster`, `aws elbv2 delete-load-balancer`, `aws ecr delete-repository --force`.
