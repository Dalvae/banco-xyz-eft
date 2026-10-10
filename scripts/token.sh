#!/usr/bin/env bash
# saca un access token del auth-server con curl, haciendo el authorization code + PKCE
# (lo mismo que hace el navegador: authorize -> login -> code -> token)
# uso: scripts/token.sh web|mobile|atm|ops [usuario]
set -euo pipefail
cd "$(dirname "$0")/.."
source .env

AUTH=http://localhost:9000
REDIRECT=http://127.0.0.1:8080/callback
canal=${1:?uso: token.sh web|mobile|atm|ops [usuario]}
usuario=${2:-demo}

case "$canal" in
  web)    client=bank-web;    scope="web:read" ;;
  mobile) client=bank-mobile; scope="mobile:read" ;;
  atm)    client=bank-atm;    scope="atm:balance%20atm:withdraw" ;;
  ops)
    # este es client_credentials, sin usuario
    curl -s -u "ops-notificaciones:$OPS_CLIENT_SECRET" -d grant_type=client_credentials \
      -d scope="notificaciones:read notificaciones:admin" "$AUTH/oauth2/token" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
    exit 0 ;;
  *) echo "canal desconocido: $canal" >&2; exit 1 ;;
esac

# pkce: el verifier queda aca y al auth-server le mando el hash
verifier=$(openssl rand -hex 40)
challenge=$(printf '%s' "$verifier" | openssl dgst -sha256 -binary | openssl base64 | tr '+/' '-_' | tr -d '=\n')
jar=$(mktemp)
trap 'rm -f "$jar"' EXIT

# 1. authorize sin sesion, el auth-server manda al login
curl -s -o /dev/null -c "$jar" -b "$jar" -H 'Accept: text/html' \
  "$AUTH/oauth2/authorize?response_type=code&client_id=$client&scope=$scope&redirect_uri=$REDIRECT&code_challenge=$challenge&code_challenge_method=S256"
# 2. login (el form trae csrf)
csrf=$(curl -s -c "$jar" -b "$jar" "$AUTH/login" | grep -o 'name="_csrf" type="hidden" value="[^"]*"' | sed 's/.*value="//;s/"$//')
siguiente=$(curl -s -o /dev/null -c "$jar" -b "$jar" -w '%{redirect_url}' \
  --data-urlencode "username=$usuario" --data-urlencode "password=$DEMO_PASSWORD" --data-urlencode "_csrf=$csrf" "$AUTH/login")
# 3. vuelve al authorize ya logeado y redirige al callback con el code
callback=$(curl -s -o /dev/null -c "$jar" -b "$jar" -w '%{redirect_url}' "$siguiente")
code=$(printf '%s' "$callback" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
if [ -z "$code" ]; then echo "no llego el code, revisa usuario/clave" >&2; exit 1; fi

# 4. cambio el code por el token
curl -s -d client_id=$client -d grant_type=authorization_code -d "code=$code" -d "redirect_uri=$REDIRECT" \
  -d "code_verifier=$verifier" "$AUTH/oauth2/token" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
