#!/bin/sh
set -eu

COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.prod}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "$BACKUP_DIR"

docker compose \
  --env-file "$ENV_FILE" \
  -f "$COMPOSE_FILE" \
  exec -T postgres \
  sh -c 'pg_dumpall --clean --if-exists --username "$POSTGRES_USER"' \
  | gzip > "$BACKUP_DIR/postgres-$TIMESTAMP.sql.gz"

find "$BACKUP_DIR" \
  -type f \
  -name 'postgres-*.sql.gz' \
  -mtime "+$RETENTION_DAYS" \
  -delete
