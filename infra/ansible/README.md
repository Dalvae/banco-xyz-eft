# Kafka en una EC2 con Ansible

En AWS va solo kafka (1 broker kraft + kafka-ui) en una ec2 t3.medium, us-east-1, sin ALB ni balanceador.
El resto del sistema corre local con `docker compose` y se conecta a este kafka.
Pensado para AWS Academy Learner Lab (no crea roles iam), pero sirve en una cuenta normal.

## Pasos

```bash
cd infra/ansible

# 1. credenciales: en el lab, "AWS Details" > "Show" > copiar el bloque y pegarlo en ~/.aws/credentials
#    (perfil [default], incluye aws_session_token). se vuelven a pegar cada vez que se reinicia el lab
aws sts get-caller-identity       # tiene que mostrar la cuenta, si falla estan vencidas

# 2. colecciones
ansible-galaxy collection install -r requirements.yml

# 3. crear la ec2 (mi_ip = mi ip publica: el ssh, el 9094 y kafka-ui solo se abren para esa ip)
#    opcion A, key pair propio:
ansible-playbook provision.yml -e mi_ip=$(curl -s ifconfig.me)/32
#    opcion B, learner lab con vockey (bajar labsuser.pem de "AWS Details" a ~/.ssh y chmod 600):
ansible-playbook provision.yml -e mi_ip=$(curl -s ifconfig.me)/32 \
  -e crear_key=false -e key_name=vockey -e key_file=~/.ssh/labsuser.pem \
  -e instance_profile=LabInstanceProfile      # el perfil es opcional

# 4. instalar docker y levantar kafka
ansible-playbook deploy.yml
#    al final imprime la linea para el .env local:  KAFKA_BOOTSTRAP=<ip>:9094

# 5. al terminar, borrar todo (con vockey agrega -e crear_key=false para no tocar el key pair)
ansible-playbook teardown.yml
```

Despues se levanta el resto local: en el `.env` de la raiz se deja `COMPOSE_PROFILES=` (sin `kafka-local`, asi no
parte el kafka de aca) y `KAFKA_BOOTSTRAP=<ip>:9094`, y `docker compose up -d`. Todo el paso a paso, y los comandos para
revisar la ec2 por ssh y ver que los servicios locales usan este kafka, estan en `despliegue.md`.
Si cambia mi ip publica hay que volver a correr `provision.yml` (actualiza el security group).

Variables en `group_vars/all.yml`: `instance_type`, `kafka_externo_port` (9094), `kafka_ui_port` (8090),
`topicos` (`banco.transacciones` con 3 particiones, `banco.alertas-seguridad` con 1), `instance_profile`.
La ami se busca sola por ssm (no esta fija). No hay claves ni vault: kafka va en plaintext y protegido solo por el security group.

## Ojo con el lab

- el lab se apaga solo a las 4 horas (o cuando uno termina la sesion): hay que volver a iniciarlo.
- al reiniciar la ec2 la ip publica cambia: kafka anuncia la ip vieja, asi que hay que correr
  `provision.yml` y `deploy.yml` de nuevo y actualizar `KAFKA_BOOTSTRAP` en el `.env`.
- las credenciales tambien cambian en cada sesion.
- `inventory.ini` y las llaves `.pem` no se suben al repo.
