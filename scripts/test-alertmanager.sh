#!/bin/sh
set -eu

ENV_FILE="${ENV_FILE:-.env.prod}"
ALERTMANAGER_URL="${ALERTMANAGER_URL:-}"

case "$ENV_FILE" in
  /*) ;;
  *) ENV_FILE="./$ENV_FILE" ;;
esac

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required to send the notification test." >&2
  exit 1
fi

if [ -z "$ALERTMANAGER_URL" ]; then
  if [ ! -f "$ENV_FILE" ]; then
    echo "Missing $ENV_FILE. Set ENV_FILE or ALERTMANAGER_URL." >&2
    exit 1
  fi

  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
  ALERTMANAGER_URL="http://127.0.0.1:${ALERTMANAGER_PORT:-9093}"
fi

started_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

curl --fail --silent --show-error \
  --header "Content-Type: application/json" \
  --data "[
    {
      \"labels\": {
        \"alertname\": \"CuratorNotificationTest\",
        \"severity\": \"critical\",
        \"application\": \"alertmanager\"
      },
      \"annotations\": {
        \"summary\": \"Curator production notification test\",
        \"description\": \"This is a controlled test alert. No service is failing.\"
      },
      \"startsAt\": \"$started_at\",
      \"generatorURL\": \"https://github.com/Nemati213/ai-assistant\"
    }
  ]" \
  "$ALERTMANAGER_URL/api/v2/alerts"

echo "Test alert submitted. Waiting for the firing notification..."
sleep 5

resolved_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

curl --fail --silent --show-error \
  --header "Content-Type: application/json" \
  --data "[
    {
      \"labels\": {
        \"alertname\": \"CuratorNotificationTest\",
        \"severity\": \"critical\",
        \"application\": \"alertmanager\"
      },
      \"annotations\": {
        \"summary\": \"Curator production notification test\",
        \"description\": \"This is a controlled test alert. No service is failing.\"
      },
      \"startsAt\": \"$started_at\",
      \"endsAt\": \"$resolved_at\",
      \"generatorURL\": \"https://github.com/Nemati213/ai-assistant\"
    }
  ]" \
  "$ALERTMANAGER_URL/api/v2/alerts"

echo "Test alert resolved. The recovery notification may take up to 10 seconds."
sleep 10
echo "Notification test completed."
