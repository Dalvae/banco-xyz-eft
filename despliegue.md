# despliegue en aws

en aws levanto **solo kafka**, en una ec2 (asi lo pidio el profe). el resto del sistema (gateway, eureka, config, auth, los 3 ms, los 3 bff, notificaciones, mysql y el batch) corre con el docker-compose en mi maquina y se conecta a ese broker por la ip publica de la ec2.

no uso alb ni balanceador de aws: el balanceo y enrutamiento que pide el enunciado lo hacen spring cloud gateway + spring cloud loadbalancer + eureka, igual que en local.

```
 mi maquina (docker-compose, sin el profile kafka-local)            aws us-east-1
 +---------------------------------------------------+            +--------------------------------+
 | api-gateway, bff-*, ms-*, eureka, config, auth,   |   9094     | ec2 t3.medium (amazon linux)   |
 | mysql, batch                                      | ---------> |  docker compose:               |
 | ms-pagos (productor) / notificaciones (consumidor) |  plaintext |   kafka (1 broker kraft)       |
 |   KAFKA_BOOTSTRAP=<ip publica>:9094               |            |   kafka-ui :8090               |
 +---------------------------------------------------+            +--------------------------------+
                                       security group: 22, 9094 y 8090 solo desde mi ip
```

lo probe pensando en el **aws academy learner lab** (credenciales temporales con session token, region us-east-1, sin poder crear roles iam), pero sirve igual en una cuenta normal.

## camino principal: ansible (infra/ansible)

el playbook hace todo sin clickear en la consola. detalle de variables en [infra/ansible/README.md](infra/ansible/README.md).

### 1. credenciales

en el learner lab: "AWS Details" -> "Show" -> copiar el bloque de credenciales a `~/.aws/credentials` (perfil `[default]`, trae `aws_session_token`). se vencen cuando se apaga el lab, hay que pegarlas de nuevo cada sesion.

```bash
aws configure set region us-east-1
aws sts get-caller-identity       # si falla, las credenciales estan vencidas
```

para el ssh se usa la llave del lab: bajar `labsuser.pem` desde "AWS Details" a `~/.ssh/` y `chmod 600 ~/.ssh/labsuser.pem`.

### 2. crear la ec2 y levantar kafka

```bash
cd infra/ansible
ansible-galaxy collection install -r requirements.yml

# mi_ip: el ssh, el 9094 y kafka-ui solo se abren para mi ip publica
ansible-playbook provision.yml -e mi_ip=$(curl -s ifconfig.me)/32 \
  -e crear_key=false -e key_name=vockey -e key_file=~/.ssh/labsuser.pem

# instala docker + compose, copia el compose de kafka (templates/kafka-compose.yaml.j2), lo levanta,
# crea los topicos banco.transacciones (3 particiones) y banco.alertas-seguridad (1) y prueba el 9094
ansible-playbook deploy.yml
# al final imprime: KAFKA_BOOTSTRAP=<ip>:9094
```

fuera del lab (con un key pair propio) basta con `ansible-playbook provision.yml -e mi_ip=$(curl -s ifconfig.me)/32`.

### 3. apuntar el compose local a la ec2

en el `.env`:

```bash
COMPOSE_PROFILES=                  # sin kafka-local: no se levanta el kafka ni el kafka-ui de aca
KAFKA_BOOTSTRAP=<ip publica>:9094
```

```bash
cd ../..
docker-compose stop kafka kafka-ui    # si estaban arriba
docker-compose up -d                  # recrea los servicios con el KAFKA_BOOTSTRAP nuevo
docker-compose exec ms-pagos env | grep KAFKA
```

todos los servicios leen `${KAFKA_BOOTSTRAP:-kafka:9092}`, y los `depends_on` hacia kafka tienen `required: false`, asi que sin el profile arrancan igual. si la ec2 no responde un rato, los retiros y transferencias igual se hacen: el evento queda en el outbox de ms-pagos y se manda solo cuando vuelve kafka.

### 4. evidencia kafka en ec2

```bash
scripts/evidencia-kafka-ec2.sh <ip publica> ~/.ssh/labsuser.pem
```

genera `evidencias/kafka-ec2.txt` con:

1. `aws ec2 describe-instances` filtrado por el tag `Project=banco-xyz-eft` (id, tipo, estado, ip y dns publico)
2. por ssh en la ec2: `hostname`, ip y dns publico sacados de la metadata (imdsv2), `docker compose ps` y `kafka-topics.sh --describe`
3. el `KAFKA_BOOTSTRAP` que estan usando ms-pagos y notificaciones en local
4. una transferencia por el gateway con token
5. ese evento leido en la ec2 con `kafka-console-consumer.sh --from-beginning`
6. `GET /api/notificaciones`: notificaciones (local) lo consumio desde la ec2, y el grupo con lag 0

capturas a mano:

- kafka-ui de la ec2 (`http://<ip>:8090`) con los mensajes de `banco.transacciones` y el consumidor `notificaciones`
- consola de aws -> ec2 -> instances, con `banco-xyz-eft` en running y la ip publica
- la terminal con el ssh a la ec2 y `docker ps`

el script lo probe en mi mac con `--local` (sin aws ni ssh), contra un kafka levantado con la misma plantilla del playbook y anunciado por ip: `evidencias/kafka-ec2-simulado-local.txt`.

### 5. borrar todo

```bash
cd infra/ansible
ansible-playbook teardown.yml -e crear_key=false     # con vockey, para no tocar el key pair del lab
```

y en el `.env` volver a `COMPOSE_PROFILES=kafka-local` y borrar `KAFKA_BOOTSTRAP`.

### ojo con el learner lab

- el lab se apaga solo a las 4 horas y las credenciales cambian en cada sesion.
- si la ec2 se reinicia cambia la ip publica, y kafka anuncia la ip vieja: hay que correr `provision.yml` y `deploy.yml` de nuevo y cambiar `KAFKA_BOOTSTRAP` en el `.env`.
- si cambia mi ip publica (otra red), `provision.yml` actualiza el security group.

## alternativa manual con aws-cli

lo mismo que hace el playbook, a mano:

```bash
export AWS_REGION=us-east-1
MI_IP=$(curl -s ifconfig.me)/32

# security group: ssh, kafka externo y kafka-ui solo desde mi ip
SG=$(aws ec2 create-security-group --group-name banco-xyz-eft-sg --description "kafka banco xyz eft" --query GroupId --output text)
for p in 22 9094 8090; do
  aws ec2 authorize-security-group-ingress --group-id $SG --protocol tcp --port $p --cidr $MI_IP
done

# amazon linux 2023 (la ami se busca en ssm), t3.medium, con el tag que usa el script de evidencia
AMI=$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 --query Parameter.Value --output text)
ID=$(aws ec2 run-instances --image-id $AMI --instance-type t3.medium --key-name vockey --security-group-ids $SG \
  --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=20,VolumeType=gp3}' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=banco-xyz-eft},{Key=Project,Value=banco-xyz-eft}]' \
  --query 'Instances[0].InstanceId' --output text)
aws ec2 wait instance-running --instance-ids $ID
IP=$(aws ec2 describe-instances --instance-ids $ID --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
echo $IP

# el compose de kafka sale de la misma plantilla del playbook, cambiando las variables a mano
sed -e "s/{{ kafka_externo_port }}/9094/g" -e "s/{{ kafka_ui_port }}/8090/g" -e "s/{{ ansible_host }}/$IP/g" \
  infra/ansible/templates/kafka-compose.yaml.j2 > /tmp/kafka-compose.yaml
scp -i ~/.ssh/labsuser.pem /tmp/kafka-compose.yaml ec2-user@$IP:docker-compose.yaml

ssh -i ~/.ssh/labsuser.pem ec2-user@$IP <<'EOF'
sudo dnf install -y docker && sudo systemctl enable --now docker
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -sL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose && sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
sudo mkdir -p /opt/kafka-banco && sudo mv docker-compose.yaml /opt/kafka-banco/
cd /opt/kafka-banco && sudo docker compose up -d --wait
sudo docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic banco.transacciones --partitions 3 --replication-factor 1
sudo docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic banco.alertas-seguridad --partitions 1 --replication-factor 1
EOF
```

(en el camino manual docker queda con `sudo`; el playbook agrega `ec2-user` al grupo docker, que es lo que asume el script de evidencia. a mano: `sudo usermod -aG docker ec2-user` y volver a entrar por ssh.)

despues se sigue desde el paso 3. para borrar: `aws ec2 terminate-instances --instance-ids $ID`, esperar con `aws ec2 wait instance-terminated --instance-ids $ID` y `aws ec2 delete-security-group --group-id $SG`.

## mejora futura: todo en aws

esto no lo hice (en el learner lab no se pueden crear roles iam y msk/rds/alb cuestan), pero el proyecto ya esta preparado: cada servicio es una imagen, la config sale del config-server y todo lo que cambia entre ambientes va por variables de entorno (`AUTH_URL`, `EUREKA_URL`, `MYSQL_HOST`, `KAFKA_BOOTSTRAP`, claves).

| hoy | en aws |
|---|---|
| imagenes `banco-xyz/*` construidas en local | **ecr**, un repositorio por servicio (`docker build --platform linux/amd64` desde un mac arm) |
| docker-compose | **ecs fargate**, un servicio por microservicio, con cloud map para los nombres (`config-server.banco.local`) |
| kafka en una ec2 | **amazon msk** con 3 brokers (uno por zona), replication factor 3 y `KAFKA_REPLICAS=3` en `config-repo/ms-pagos.yml` |
| mysql con un schema por servicio | **rds mysql** (uno por servicio o uno con usuarios separados, como el `docker/mysql/init.sh`) |
| https con certificado autofirmado en el gateway | **alb** con certificado de **acm** delante del gateway (el gateway ya confia en los `X-Forwarded-*` de la vpc, `trusted-proxies`) |
| `.env` | **secrets manager**, inyectado en las task definitions con `secrets` |
| `docker-compose logs` | **cloudwatch logs** (driver `awslogs`), buscando por traceId |
| `--scale ms-cuentas=2` | **application auto scaling** del servicio ecs (target tracking de cpu al 60 %) |
| `docker-compose run batch` | tarea ecs programada con **eventbridge scheduler**, con `RetryPolicy` (equivalente al `restart: on-failure`) |

comandos base de ese camino, como referencia:

```bash
CUENTA=$(aws sts get-caller-identity --query Account --output text); ECR=$CUENTA.dkr.ecr.us-east-1.amazonaws.com
aws ecr get-login-password | docker login --username AWS --password-stdin $ECR
for s in config-server eureka-server api-gateway auth-server ms-cuentas ms-pagos ms-clientes bff-web bff-mobile bff-atm notificaciones batch; do
  aws ecr create-repository --repository-name banco-xyz/$s || true
  docker build --platform linux/amd64 -f $s/Dockerfile -t $ECR/banco-xyz/$s:1.0.0 . && docker push $ECR/banco-xyz/$s:1.0.0
done
aws ecs create-cluster --cluster-name banco-xyz --capacity-providers FARGATE
aws secretsmanager create-secret --name banco-xyz/prod --secret-string file://secretos.json
aws ecs register-task-definition --cli-input-json file://ecs/ms-cuentas.json
aws ecs create-service --cluster banco-xyz --service-name ms-cuentas --task-definition ms-cuentas --desired-count 2 \
  --launch-type FARGATE --network-configuration "awsvpcConfiguration={subnets=[subnet-a,subnet-b],securityGroups=[sg-x]}"
aws application-autoscaling register-scalable-target --service-namespace ecs --resource-id service/banco-xyz/ms-cuentas \
  --scalable-dimension ecs:service:DesiredCount --min-capacity 2 --max-capacity 6
```
