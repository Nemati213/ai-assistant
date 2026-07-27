#!/bin/sh
set -eu

SCRIPT_DIR="$(unset CDPATH; cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(unset CDPATH; cd -- "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/compose.staging.yml"
EXPECTED_CONFIRMATION="run-isolated-staging-load"
RATES="${STAGING_RATES:-10 25 50 100}"
DURATION="${STAGING_STEP_DURATION:-10s}"
DRAIN_TIMEOUT_SECONDS="${STAGING_DRAIN_TIMEOUT_SECONDS:-300}"
VK_PORT="${STAGING_VK_PORT:-18081}"

if [ "${STAGING_LOAD_CONFIRM:-}" != "$EXPECTED_CONFIRMATION" ]; then
  echo "Set STAGING_LOAD_CONFIRM=$EXPECTED_CONFIRMATION to continue." >&2
  exit 1
fi

duration_seconds() {
  case "$1" in
    *s) echo "${1%s}" ;;
    *m) echo "$(( ${1%m} * 60 ))" ;;
    *)
      echo "STAGING_STEP_DURATION must use whole seconds or minutes, for example 30s or 2m." >&2
      exit 1
      ;;
  esac
}

query_db() {
  user="$1"
  database="$2"
  sql="$3"
  docker compose -f "$COMPOSE_FILE" exec -T postgres \
    psql --no-psqlrc --tuples-only --no-align \
    --username "$user" --dbname "$database" \
    --command "$sql"
}

workflow_count() {
  query_db staging_orchestrator orchestrator_db \
    "SELECT count(*) FROM workflow_states;"
}

completed_count() {
  query_db staging_orchestrator orchestrator_db \
    "SELECT count(*) FROM workflow_states WHERE status = 'COMPLETED';"
}

pending_outbox_count() {
  vk_pending="$(
    query_db staging_vk vk_connector_db "
      SELECT
        (SELECT count(*) FROM vk_webhook_outbox WHERE published_at IS NULL)
        + (SELECT count(*) FROM vk_group_config_status_outbox WHERE published_at IS NULL)
        + (SELECT count(*) FROM vk_outgoing_deliveries
           WHERE result_published_at IS NULL
             AND status IN ('SUCCEEDED', 'FAILED'));
    "
  )"
  tg_pending="$(
    query_db staging_tg tg_connector_db "
      SELECT
        (SELECT count(*) FROM vk_group_config_outbox WHERE published_at IS NULL)
        + (SELECT count(*) FROM curator_intake_outbox WHERE published_at IS NULL)
        + (SELECT count(*) FROM curator_decision_outbox WHERE published_at IS NULL)
        + (SELECT count(*) FROM billing_transactions
           WHERE result_published_at IS NULL
             AND status IN ('CHARGED', 'INSUFFICIENT_FUNDS'))
        + (SELECT count(*) FROM billing_refunds
           WHERE result_published_at IS NULL
             AND status IN ('REFUNDED', 'REJECTED'));
    "
  )"
  orchestrator_pending="$(
    query_db staging_orchestrator orchestrator_db \
      "SELECT count(*) FROM outbox_events WHERE published_at IS NULL;"
  )"
  ai_pending="$(
    query_db staging_ai ai_service_db "
      SELECT count(*) FROM ai_generation_requests
      WHERE result_published_at IS NULL
        AND status IN ('COMPLETED', 'FAILED');
    "
  )"
  echo "$((vk_pending + tg_pending + orchestrator_pending + ai_pending))"
}

wait_for_target() {
  target_total="$1"
  target_completed="$2"
  elapsed=0
  while [ "$elapsed" -lt "$DRAIN_TIMEOUT_SECONDS" ]; do
    current_total="$(workflow_count)"
    current_completed="$(completed_count)"
    pending="$(pending_outbox_count)"
    if [ "$current_total" -ge "$target_total" ] \
        && [ "$current_completed" -ge "$target_completed" ] \
        && [ "$pending" -eq 0 ]; then
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  return 1
}

seconds="$(duration_seconds "$DURATION")"
case "$seconds" in
  ''|*[!0-9]*|0)
    echo "STAGING_STEP_DURATION must be a positive whole duration." >&2
    exit 1
    ;;
esac

base_total="$(workflow_count)"
base_completed="$(completed_count)"
expected_delta=0

for rate in $RATES; do
  case "$rate" in
    ''|*[!0-9]*|0)
      echo "STAGING_RATES must contain positive whole numbers." >&2
      exit 1
      ;;
  esac

  echo "Running full workflow at ${rate} RPS for ${DURATION}..."
  LOAD_TEST_CONFIRM=non-production-load-test \
  LOAD_WRITE_CONFIRM=create-staging-events \
  LOAD_BASE_URL="http://host.docker.internal:$VK_PORT" \
  LOAD_VK_GROUP_ID=900001 \
  LOAD_VK_SECRET=staging-vk-secret \
  LOAD_MODE=write \
  LOAD_RATE="$rate" \
  LOAD_DURATION="$DURATION" \
  LOAD_PREALLOCATED_VUS="$rate" \
  LOAD_MAX_VUS="$((rate * 3))" \
    "$SCRIPT_DIR/run-load-test.sh"

  expected_delta=$((expected_delta + rate * seconds))
  target_total=$((base_total + expected_delta))
  target_completed=$((base_completed + expected_delta))

  if ! wait_for_target "$target_total" "$target_completed"; then
    echo "The asynchronous pipeline did not drain after the ${rate} RPS step." >&2
    query_db staging_orchestrator orchestrator_db \
      "SELECT status, count(*) FROM workflow_states GROUP BY status ORDER BY status;" >&2
    exit 1
  fi

  echo "The ${rate} RPS step completed ${rate} * ${seconds} workflows and drained."
done

echo "Final workflow states:"
query_db staging_orchestrator orchestrator_db \
  "SELECT status, count(*) FROM workflow_states GROUP BY status ORDER BY status;"

echo "Kafka consumer lag snapshot:"
docker compose -f "$COMPOSE_FILE" exec -T kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 \
  --all-groups \
  --describe

echo "Load ladder passed with ${expected_delta} completed end-to-end workflows."
