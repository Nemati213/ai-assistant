#!/bin/sh
set -eu

SCRIPT_DIR="$(unset CDPATH; cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(unset CDPATH; cd -- "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/compose.staging.yml"
EXPECTED_CONFIRMATION="remove-isolated-staging"

if [ "${STAGING_DOWN_CONFIRM:-}" != "$EXPECTED_CONFIRMATION" ]; then
  echo "Set STAGING_DOWN_CONFIRM=$EXPECTED_CONFIRMATION to remove staging data." >&2
  exit 1
fi

docker compose -f "$COMPOSE_FILE" down --volumes --remove-orphans
