#!/bin/sh
set -eu

SCRIPT_DIR="$(unset CDPATH; cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(unset CDPATH; cd -- "$SCRIPT_DIR/.." && pwd)"
. "$SCRIPT_DIR/lib/postgres-backup-common.sh"

ENV_FILE="${ENV_FILE:-.env.prod}"
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:17.10-alpine3.23}"
DRILL_CONTAINER_NAME="${DRILL_CONTAINER_NAME:-curator-postgres-restore-drill-$$}"
KEEP_DRILL_CONTAINER="${KEEP_DRILL_CONTAINER:-0}"
WAIT_ATTEMPTS="${WAIT_ATTEMPTS:-60}"

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 backups/postgres-TIMESTAMP.tar" >&2
  exit 1
fi

BACKUP_FILE="$1"

require_command docker
require_command sha256sum
require_command tar

if [ ! -f "$ENV_FILE" ]; then
  echo "Environment file not found: $ENV_FILE" >&2
  exit 1
fi

case "$WAIT_ATTEMPTS" in
  ''|*[!0-9]*|0)
    echo "WAIT_ATTEMPTS must be a positive integer." >&2
    exit 1
    ;;
esac

WORK_DIR="$(mktemp -d)"
container_started=0

cleanup() {
  rm -rf "$WORK_DIR"
  if [ "$container_started" -eq 1 ] && [ "$KEEP_DRILL_CONTAINER" != "1" ]; then
    docker rm --force "$DRILL_CONTAINER_NAME" > /dev/null 2>&1 || true
  fi
}
trap cleanup EXIT HUP INT TERM

verify_backup_archive "$BACKUP_FILE" "$WORK_DIR"

docker run \
  --detach \
  --name "$DRILL_CONTAINER_NAME" \
  --env-file "$ENV_FILE" \
  --mount "type=bind,source=$REPO_ROOT/infra/postgres/init,target=/docker-entrypoint-initdb.d,readonly" \
  --security-opt no-new-privileges:true \
  "$POSTGRES_IMAGE" > /dev/null
container_started=1

attempt=1
until docker exec "$DRILL_CONTAINER_NAME" \
  sh -c 'pg_isready --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"' \
  > /dev/null 2>&1
do
  if [ "$attempt" -ge "$WAIT_ATTEMPTS" ]; then
    echo "Restore drill PostgreSQL did not become ready." >&2
    docker logs "$DRILL_CONTAINER_NAME" >&2
    exit 1
  fi
  attempt="$((attempt + 1))"
  sleep 2
done

started_at="$(date +%s)"
for database in $POSTGRES_DATABASES; do
  echo "Drill restoring $database."
  docker exec -i "$DRILL_CONTAINER_NAME" \
    sh -c '
      case "$1" in
        tg_connector_db) restore_user="$TG_DB_USERNAME" ;;
        vk_connector_db) restore_user="$VK_DB_USERNAME" ;;
        orchestrator_db) restore_user="$ORCHESTRATOR_DB_USERNAME" ;;
        ai_service_db) restore_user="$AI_DB_USERNAME" ;;
        *) echo "Unsupported database: $1" >&2; exit 1 ;;
      esac
      exec pg_restore \
        --clean \
        --if-exists \
        --no-owner \
        --no-acl \
        --single-transaction \
        --username "$restore_user" \
        --dbname "$1"
    ' sh "$database" < "$WORK_DIR/$database.dump"

  table_count="$(
    docker exec "$DRILL_CONTAINER_NAME" \
      sh -c '
        psql \
          --username "$POSTGRES_USER" \
          --dbname "$1" \
          --tuples-only \
          --no-align \
          --command "
            SELECT COUNT(*)
            FROM pg_catalog.pg_class AS relation
            JOIN pg_catalog.pg_namespace AS namespace
              ON namespace.oid = relation.relnamespace
            WHERE relation.relkind IN ('"'"'r'"'"', '"'"'p'"'"')
              AND namespace.nspname NOT IN ('"'"'pg_catalog'"'"', '"'"'information_schema'"'"');
          "
      ' sh "$database"
  )"
  expected_count="$(
    sed -n "s/^${database}_tables=//p" "$WORK_DIR/manifest.env"
  )"
  if [ "$table_count" != "$expected_count" ]; then
    echo "Table count mismatch for $database: expected $expected_count, got $table_count" >&2
    exit 1
  fi
done

duration="$(( $(date +%s) - started_at ))"
echo "Restore drill passed in ${duration}s using $DRILL_CONTAINER_NAME."

if [ "$KEEP_DRILL_CONTAINER" = "1" ]; then
  echo "Restore drill container was kept for additional verification."
fi
