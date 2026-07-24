#!/bin/sh
set -eu

COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.prod}"

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 backups/postgres-YYYYMMDDTHHMMSSZ.sql.gz" >&2
  exit 1
fi

BACKUP_FILE="$1"

if [ ! -f "$BACKUP_FILE" ]; then
  echo "Backup file not found: $BACKUP_FILE" >&2
  exit 1
fi

case "$BACKUP_FILE" in
  *.sql.gz) ;;
  *)
    echo "Backup file must be a gzipped SQL dump (*.sql.gz)." >&2
    exit 1
    ;;
esac

echo "Restoring $BACKUP_FILE into postgres service from $COMPOSE_FILE."
echo "This will apply DROP statements from the dump. Stop application services first."

gzip -dc "$BACKUP_FILE" \
  | docker compose \
      --env-file "$ENV_FILE" \
      -f "$COMPOSE_FILE" \
      exec -T postgres \
      sh -c 'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=on'
