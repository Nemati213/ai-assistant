#!/bin/sh
set -eu

SCRIPT_DIR="$(unset CDPATH; cd -- "$(dirname -- "$0")" && pwd)"
. "$SCRIPT_DIR/lib/postgres-backup-common.sh"

COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.prod}"
POSTGRES_SERVICE="${POSTGRES_SERVICE:-postgres}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
ARCHIVE_NAME="postgres-$TIMESTAMP.tar"
FINAL_ARCHIVE="$BACKUP_DIR/$ARCHIVE_NAME"

require_command docker
require_command sha256sum
require_command tar

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "Compose file not found: $COMPOSE_FILE" >&2
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "Environment file not found: $ENV_FILE" >&2
  exit 1
fi

case "$RETENTION_DAYS" in
  ''|*[!0-9]*)
    echo "RETENTION_DAYS must be a non-negative integer." >&2
    exit 1
    ;;
esac

umask 077
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

WORK_DIR="$(mktemp -d "$BACKUP_DIR/.postgres-backup.XXXXXX")"
TEMP_ARCHIVE="$BACKUP_DIR/.$ARCHIVE_NAME.tmp"
VERIFY_DIR=""

cleanup() {
  rm -rf "$WORK_DIR"
  if [ -n "$VERIFY_DIR" ]; then
    rm -rf "$VERIFY_DIR"
  fi
  rm -f "$TEMP_ARCHIVE"
}
trap cleanup EXIT HUP INT TERM

compose() {
  docker compose \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

if ! compose ps --services --status running | grep -qx "$POSTGRES_SERVICE"; then
  echo "PostgreSQL service is not running: $POSTGRES_SERVICE" >&2
  exit 1
fi

SERVER_VERSION="$(
  compose exec -T "$POSTGRES_SERVICE" \
    sh -c 'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --tuples-only --no-align --command "SHOW server_version"'
)"

{
  echo "format=$POSTGRES_BACKUP_FORMAT"
  echo "created_at=$TIMESTAMP"
  echo "postgres_server_version=$SERVER_VERSION"
  echo "databases=$POSTGRES_DATABASES"
} > "$WORK_DIR/manifest.env"

for database in $POSTGRES_DATABASES; do
  dump_file="$WORK_DIR/$database.dump"
  echo "Backing up $database."

  compose exec -T "$POSTGRES_SERVICE" \
    sh -c '
      exec pg_dump \
        --format=custom \
        --compress=9 \
        --no-owner \
        --no-acl \
        --username "$POSTGRES_USER" \
        --dbname "$1"
    ' sh "$database" > "$dump_file"

  if [ ! -s "$dump_file" ]; then
    echo "Backup is empty for database: $database" >&2
    exit 1
  fi

  compose exec -T "$POSTGRES_SERVICE" \
    pg_restore --list < "$dump_file" > /dev/null

  table_count="$(
    compose exec -T "$POSTGRES_SERVICE" \
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
  echo "${database}_tables=$table_count" >> "$WORK_DIR/manifest.env"
done

(
  cd "$WORK_DIR"
  sha256sum ./*.dump > SHA256SUMS
)

tar -cf "$TEMP_ARCHIVE" \
  -C "$WORK_DIR" \
  manifest.env \
  SHA256SUMS \
  tg_connector_db.dump \
  vk_connector_db.dump \
  orchestrator_db.dump \
  ai_service_db.dump

mv "$TEMP_ARCHIVE" "$FINAL_ARCHIVE"
(
  cd "$BACKUP_DIR"
  sha256sum "$ARCHIVE_NAME" > "$ARCHIVE_NAME.sha256"
)

VERIFY_DIR="$(mktemp -d "$BACKUP_DIR/.postgres-verify.XXXXXX")"
verify_backup_archive "$FINAL_ARCHIVE" "$VERIFY_DIR"
rm -rf "$VERIFY_DIR"
VERIFY_DIR=""

find "$BACKUP_DIR" \
  -type f \
  -name 'postgres-*.tar' \
  -mtime "+$RETENTION_DAYS" \
  -delete
find "$BACKUP_DIR" \
  -type f \
  -name 'postgres-*.tar.sha256' \
  -mtime "+$RETENTION_DAYS" \
  -delete

echo "Backup created: $FINAL_ARCHIVE"
echo "Checksum: $FINAL_ARCHIVE.sha256"
