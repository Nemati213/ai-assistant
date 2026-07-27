#!/bin/sh
set -eu

SCRIPT_DIR="$(unset CDPATH; cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(unset CDPATH; cd -- "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/compose.staging.yml"
EXPECTED_CONFIRMATION="isolated-staging-only"

if [ "${STAGING_CONFIRM:-}" != "$EXPECTED_CONFIRMATION" ]; then
  echo "The staging stack creates disposable databases and synthetic events." >&2
  echo "Set STAGING_CONFIRM=$EXPECTED_CONFIRMATION to continue." >&2
  exit 1
fi

if ! command -v docker > /dev/null 2>&1; then
  echo "Docker with Compose v2 is required." >&2
  exit 1
fi

docker compose -f "$COMPOSE_FILE" up -d --build --wait --wait-timeout 600

attempt=0
until [ "$attempt" -ge 60 ]; do
  response="$(
    docker compose -f "$COMPOSE_FILE" exec -T provider-stub \
      curl --silent --show-error \
      --header "Content-Type: application/json" \
      --data '{"type":"confirmation","group_id":"900001","secret":"staging-vk-secret","object":{}}' \
      http://vk-connector-service:8081/vk/webhook \
      2> /dev/null || true
  )"
  if [ "$response" = "staging-confirmation" ]; then
    echo "Isolated staging is ready at http://127.0.0.1:${STAGING_VK_PORT:-18081}."
    exit 0
  fi
  attempt=$((attempt + 1))
  sleep 2
done

echo "Staging services started, but the synthetic VK group did not become active." >&2
docker compose -f "$COMPOSE_FILE" ps >&2
docker compose -f "$COMPOSE_FILE" logs --tail=200 \
  tg-connector-service vk-connector-service >&2
exit 1
