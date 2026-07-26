#!/bin/sh
set -eu

SCRIPT_DIR="$(unset CDPATH; cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(unset CDPATH; cd -- "$SCRIPT_DIR/.." && pwd)"
K6_IMAGE="${K6_IMAGE:-grafana/k6:2.0.0}"
LOAD_MODE="${LOAD_MODE:-read}"
EXPECTED_CONFIRMATION="non-production-load-test"

if [ "${LOAD_TEST_CONFIRM:-}" != "$EXPECTED_CONFIRMATION" ]; then
  echo "Load tests must run only against an isolated staging environment." >&2
  echo "Set LOAD_TEST_CONFIRM=$EXPECTED_CONFIRMATION to continue." >&2
  exit 1
fi

if ! command -v docker > /dev/null 2>&1; then
  echo "Docker is required to run the pinned k6 image." >&2
  exit 1
fi

if [ -z "${LOAD_BASE_URL:-}" ]; then
  echo "LOAD_BASE_URL is required." >&2
  exit 1
fi
if [ -z "${LOAD_VK_GROUP_ID:-}" ]; then
  echo "LOAD_VK_GROUP_ID is required." >&2
  exit 1
fi
if [ -z "${LOAD_VK_SECRET:-}" ]; then
  echo "LOAD_VK_SECRET is required." >&2
  exit 1
fi

case "$LOAD_BASE_URL" in
  http://*|https://*) ;;
  *)
    echo "LOAD_BASE_URL must start with http:// or https://." >&2
    exit 1
    ;;
esac

case "$LOAD_MODE" in
  read) ;;
  write)
    if [ "${LOAD_WRITE_CONFIRM:-}" != "create-staging-events" ]; then
      echo "Write mode creates durable webhook and downstream events." >&2
      echo "Set LOAD_WRITE_CONFIRM=create-staging-events to continue." >&2
      exit 1
    fi
    ;;
  *)
    echo "LOAD_MODE must be read or write." >&2
    exit 1
    ;;
esac

export LOAD_MODE

docker run \
  --rm \
  --read-only \
  --tmpfs /tmp:size=64m,mode=1777 \
  --add-host host.docker.internal:host-gateway \
  --mount "type=bind,source=$REPO_ROOT/load-tests,target=/scripts,readonly" \
  --env LOAD_BASE_URL \
  --env LOAD_VK_GROUP_ID \
  --env LOAD_VK_SECRET \
  --env LOAD_MODE \
  --env LOAD_RATE \
  --env LOAD_DURATION \
  --env LOAD_PREALLOCATED_VUS \
  --env LOAD_MAX_VUS \
  --env LOAD_P95_MS \
  --env LOAD_P99_MS \
  "$K6_IMAGE" \
  run /scripts/vk-webhook.js
