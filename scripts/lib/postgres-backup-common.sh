#!/bin/sh

POSTGRES_BACKUP_FORMAT="curator-postgres-backup-v2"
POSTGRES_DATABASES="tg_connector_db vk_connector_db orchestrator_db ai_service_db"

require_command() {
  if ! command -v "$1" > /dev/null 2>&1; then
    echo "Required command is not available: $1" >&2
    exit 1
  fi
}

verify_backup_archive() {
  archive="$1"
  destination="$2"

  if [ ! -f "$archive" ]; then
    echo "Backup file not found: $archive" >&2
    exit 1
  fi

  case "$archive" in
    *.tar) ;;
    *)
      echo "Backup file must be a curator PostgreSQL archive (*.tar)." >&2
      exit 1
      ;;
  esac

  checksum_file="$archive.sha256"
  if [ ! -f "$checksum_file" ]; then
    echo "Backup checksum not found: $checksum_file" >&2
    exit 1
  fi

  archive_dir="$(unset CDPATH; cd -- "$(dirname -- "$archive")" && pwd)"
  archive_name="$(basename -- "$archive")"
  (
    cd "$archive_dir" || exit 1
    sha256sum -c "$archive_name.sha256"
  )

  archive_listing="$(tar -tf "$archive")"
  if printf '%s\n' "$archive_listing" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
    echo "Backup archive contains an unsafe path." >&2
    exit 1
  fi

  mkdir -p "$destination"
  tar -xf "$archive" -C "$destination"

  if ! grep -qx "format=$POSTGRES_BACKUP_FORMAT" "$destination/manifest.env"; then
    echo "Unsupported or missing backup format." >&2
    exit 1
  fi

  for required_file in manifest.env SHA256SUMS; do
    if [ ! -s "$destination/$required_file" ]; then
      echo "Backup archive is missing $required_file." >&2
      exit 1
    fi
  done

  for database in $POSTGRES_DATABASES; do
    if [ ! -s "$destination/$database.dump" ]; then
      echo "Backup archive is missing $database.dump." >&2
      exit 1
    fi

    expected_count="$(
      sed -n "s/^${database}_tables=//p" "$destination/manifest.env"
    )"
    case "$expected_count" in
      ''|*[!0-9]*)
        echo "Backup manifest has an invalid table count for $database." >&2
        exit 1
        ;;
    esac
  done

  (
    cd "$destination" || exit 1
    sha256sum -c SHA256SUMS
  )
}
