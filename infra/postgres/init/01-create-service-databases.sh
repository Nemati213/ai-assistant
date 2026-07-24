#!/bin/sh
set -eu

TG_DB_USERNAME="${TG_DB_USERNAME:-$POSTGRES_USER}"
TG_DB_PASSWORD="${TG_DB_PASSWORD:-$POSTGRES_PASSWORD}"
VK_DB_USERNAME="${VK_DB_USERNAME:-$POSTGRES_USER}"
VK_DB_PASSWORD="${VK_DB_PASSWORD:-$POSTGRES_PASSWORD}"
ORCHESTRATOR_DB_USERNAME="${ORCHESTRATOR_DB_USERNAME:-$POSTGRES_USER}"
ORCHESTRATOR_DB_PASSWORD="${ORCHESTRATOR_DB_PASSWORD:-$POSTGRES_PASSWORD}"
AI_DB_USERNAME="${AI_DB_USERNAME:-$POSTGRES_USER}"
AI_DB_PASSWORD="${AI_DB_PASSWORD:-$POSTGRES_PASSWORD}"

create_role() {
  role="$1"
  password="$2"

  if [ "$role" = "$POSTGRES_USER" ]; then
    return
  fi

  psql \
    --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=role="$role" \
    --set=password="$password" <<-'EOSQL'
CREATE ROLE :"role" LOGIN PASSWORD :'password';
EOSQL
}

create_database() {
  database="$1"
  owner="$2"

  psql \
    --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=owner="$owner" \
    --set=database="$database" <<-'EOSQL'
CREATE DATABASE :"database" OWNER :"owner";
EOSQL
}

create_role "$TG_DB_USERNAME" "$TG_DB_PASSWORD"
create_role "$VK_DB_USERNAME" "$VK_DB_PASSWORD"
create_role "$ORCHESTRATOR_DB_USERNAME" "$ORCHESTRATOR_DB_PASSWORD"
create_role "$AI_DB_USERNAME" "$AI_DB_PASSWORD"

create_database tg_connector_db "$TG_DB_USERNAME"
create_database vk_connector_db "$VK_DB_USERNAME"
create_database orchestrator_db "$ORCHESTRATOR_DB_USERNAME"
create_database ai_service_db "$AI_DB_USERNAME"
