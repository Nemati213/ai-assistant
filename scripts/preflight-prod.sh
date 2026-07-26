#!/bin/sh
set -eu

ENV_FILE="${ENV_FILE:-.env.prod}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yml}"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE. Create it from .env.prod.example." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

required_variables="
PUBLIC_DOMAIN
ACME_EMAIL
POSTGRES_USER
POSTGRES_PASSWORD
POSTGRES_DB
REDIS_PASSWORD
TG_DB_USERNAME
TG_DB_PASSWORD
VK_DB_USERNAME
VK_DB_PASSWORD
ORCHESTRATOR_DB_USERNAME
ORCHESTRATOR_DB_PASSWORD
AI_DB_USERNAME
AI_DB_PASSWORD
TELEGRAM_BOT_NAME
TELEGRAM_BOT_TOKEN
TELEGRAM_ADMIN_BOT_NAME
TELEGRAM_ADMIN_BOT_TOKEN
TELEGRAM_ADMIN_IDS
OPENROUTER_API_KEY
APP_SECRET_ENCRYPTION_KEY
GRAFANA_ADMIN_PASSWORD
"

for variable in $required_variables; do
  eval "value=\${$variable:-}"
  if [ -z "$value" ]; then
    echo "Required variable $variable is empty." >&2
    exit 1
  fi
  case "$value" in
    *replace_me*|*replace_with_*|*example.com*)
      echo "Required variable $variable still contains a placeholder." >&2
      exit 1
      ;;
  esac
done

if [ "$TELEGRAM_BOT_TOKEN" = "$TELEGRAM_ADMIN_BOT_TOKEN" ]; then
  echo "Curator and admin Telegram bots must use different tokens." >&2
  exit 1
fi

passwords="
$POSTGRES_PASSWORD
$REDIS_PASSWORD
$TG_DB_PASSWORD
$VK_DB_PASSWORD
$ORCHESTRATOR_DB_PASSWORD
$AI_DB_PASSWORD
$GRAFANA_ADMIN_PASSWORD
"

password_count="$(printf '%s\n' "$passwords" | sed '/^$/d' | wc -l)"
unique_password_count="$(
  printf '%s\n' "$passwords" | sed '/^$/d' | sort -u | wc -l
)"

if [ "$password_count" -ne "$unique_password_count" ]; then
  echo "Infrastructure and database passwords must be unique." >&2
  exit 1
fi

if ! printf '%s' "$APP_SECRET_ENCRYPTION_KEY" \
  | base64 -d 2>/dev/null \
  | wc -c \
  | grep -qx '32'; then
  echo "APP_SECRET_ENCRYPTION_KEY must be a Base64-encoded 32-byte key." >&2
  exit 1
fi

docker compose \
  --env-file "$ENV_FILE" \
  -f "$COMPOSE_FILE" \
  config --quiet

echo "Production preflight passed."
