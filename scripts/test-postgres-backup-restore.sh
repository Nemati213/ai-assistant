#!/bin/sh
set -eu

SCRIPT_DIR="$(unset CDPATH; cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(unset CDPATH; cd -- "$SCRIPT_DIR/.." && pwd)"
. "$SCRIPT_DIR/lib/postgres-backup-common.sh"

ENV_FILE="${ENV_FILE:-$REPO_ROOT/.env.prod.example}"
COMPOSE_FILE="${COMPOSE_FILE:-$REPO_ROOT/infra/postgres/backup-drill.compose.yml}"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-curator-backup-test-$$}"
DRILL_CONTAINER_NAME="${DRILL_CONTAINER_NAME:-$PROJECT_NAME-restore}"
BACKUP_DIR="$(mktemp -d)"

export COMPOSE_PROJECT_NAME="$PROJECT_NAME"

compose() {
  docker compose \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

cleanup() {
  docker rm --force "$DRILL_CONTAINER_NAME" > /dev/null 2>&1 || true
  compose down --volumes --remove-orphans > /dev/null 2>&1 || true
  rm -rf "$BACKUP_DIR"
}
trap cleanup EXIT HUP INT TERM

compose up --detach --wait postgres

for database in $POSTGRES_DATABASES; do
  compose exec -T postgres \
    sh -c '
      exec psql \
        --username "$POSTGRES_USER" \
        --dbname "$1" \
        --set=ON_ERROR_STOP=1 \
        --command "
          CREATE TABLE backup_restore_probe (
            id INTEGER PRIMARY KEY,
            payload TEXT NOT NULL
          );
          INSERT INTO backup_restore_probe (id, payload)
          VALUES (1, '"'"'alpha'"'"'), (2, '"'"'beta'"'"'), (3, '"'"'gamma'"'"');
        "
    ' sh "$database" > /dev/null
done

COMPOSE_FILE="$COMPOSE_FILE" \
ENV_FILE="$ENV_FILE" \
BACKUP_DIR="$BACKUP_DIR" \
RETENTION_DAYS=1 \
"$SCRIPT_DIR/backup-postgres.sh"

BACKUP_FILE="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'postgres-*.tar' | head -n 1)"
if [ -z "$BACKUP_FILE" ]; then
  echo "Backup test did not create an archive." >&2
  exit 1
fi

if COMPOSE_FILE="$COMPOSE_FILE" \
  ENV_FILE="$ENV_FILE" \
  sh "$SCRIPT_DIR/restore-postgres.sh" "$BACKUP_FILE" \
  > /dev/null 2>&1
then
  echo "Production restore was not blocked without confirmation." >&2
  exit 1
fi

CORRUPTED_BACKUP="$BACKUP_DIR/postgres-corrupted.tar"
cp "$BACKUP_FILE" "$CORRUPTED_BACKUP"
(
  cd "$BACKUP_DIR"
  sha256sum "$(basename "$CORRUPTED_BACKUP")" \
    > "$(basename "$CORRUPTED_BACKUP").sha256"
)
printf 'corruption' >> "$CORRUPTED_BACKUP"
if ENV_FILE="$ENV_FILE" \
  sh "$SCRIPT_DIR/drill-postgres-restore.sh" "$CORRUPTED_BACKUP" \
  > /dev/null 2>&1
then
  echo "Restore drill accepted a corrupted backup." >&2
  exit 1
fi
rm -f "$CORRUPTED_BACKUP" "$CORRUPTED_BACKUP.sha256"

ENV_FILE="$ENV_FILE" \
DRILL_CONTAINER_NAME="$DRILL_CONTAINER_NAME" \
KEEP_DRILL_CONTAINER=1 \
sh "$SCRIPT_DIR/drill-postgres-restore.sh" "$BACKUP_FILE"

for database in $POSTGRES_DATABASES; do
  restored="$(
    docker exec "$DRILL_CONTAINER_NAME" \
      sh -c '
        case "$1" in
          tg_connector_db) service_user="$TG_DB_USERNAME" ;;
          vk_connector_db) service_user="$VK_DB_USERNAME" ;;
          orchestrator_db) service_user="$ORCHESTRATOR_DB_USERNAME" ;;
          ai_service_db) service_user="$AI_DB_USERNAME" ;;
          *) echo "Unsupported database: $1" >&2; exit 1 ;;
        esac
        psql \
          --username "$service_user" \
          --dbname "$1" \
          --tuples-only \
          --no-align \
          --command "
            SELECT STRING_AGG(id || '"'"':'"'"' || payload, '"'"','"'"' ORDER BY id)
            FROM backup_restore_probe;
          "
      ' sh "$database"
  )"
  if [ "$restored" != "1:alpha,2:beta,3:gamma" ]; then
    echo "Restored fixture mismatch for $database: $restored" >&2
    exit 1
  fi
done

echo "PostgreSQL backup and restore scenario passed for all service databases."
