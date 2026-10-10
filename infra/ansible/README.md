# despliegue en ec2 con ansible

Levanta el banco en una ec2 t3.large (amazon linux 2023, us-east-1) con docker compose.
Pensado para AWS Academy Learner Lab (no crea roles iam), pero sirve en una cuenta normal.

## pasos

```bash
cd infra/ansible

# 1. credenciales: en el lab, "AWS Details" > "Show" > copiar el bloque y pegarlo en ~/.aws/credentials
#    (perfil [default], incluye aws_session_token). se vuelven a pegar cada vez que se reinicia el lab
aws sts get-caller-identity       # tiene que mostrar la cuenta, si falla estan vencidas

# 2. colecciones
ansible-galaxy collection install -r requirements.yml

# 3. crear la ec2 (mi_ip = mi ip publica, para el ssh)
#    opcion A, key pair propio:
ansible-playbook provision.yml -e mi_ip=$(curl -s ifconfig.me)/32
#    opcion B, learner lab con vockey (bajar labsuser.pem de "AWS Details" a ~/.ssh y chmod 600):
ansible-playbook provision.yml -e mi_ip=$(curl -s ifconfig.me)/32 \
  -e crear_key=false -e key_name=vockey -e key_file=~/.ssh/labsuser.pem \
  -e instance_profile=LabInstanceProfile      # el perfil es opcional

# 4. desplegar (claves en un vault, no en el repo)
cat > vault.yml <<'V'
vault_demo_password: "..."
vault_ops_client_secret: "..."
V
ansible-vault encrypt vault.yml
ansible-playbook deploy.yml -e @vault.yml --ask-vault-pass

# 5. al terminar, borrar todo (con vockey agrega -e crear_key=false para no tocar el key pair)
ansible-playbook teardown.yml
```

Variables en `group_vars/all.yml`: `instance_type` (t3.large, el lab permite hasta large),
`gateway_port`, `cuentas_replicas`, `repo_url`, `instance_profile`.
La ami se busca sola por ssm (no esta fija).

## ojo con el lab

- el lab se apaga solo a las 4 horas (o cuando uno termina la sesion): hay que volver a iniciarlo.
- al reiniciar la ec2 la ip publica cambia: volver a correr `provision.yml` (reescribe `inventory.ini`)
  o actualizar la ip a mano, y abrir el gateway con la ip nueva.
- las credenciales tambien cambian en cada sesion.
- `vault.yml`, `inventory.ini` y las llaves `.pem` no se suben al repo.
