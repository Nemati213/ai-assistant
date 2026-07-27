# Load testing

The first load target is the public VK webhook because it is the synchronous
entry point that must remain fast while the rest of the workflow is
asynchronous.

Never run these scenarios against production. Use an isolated staging
environment with test VK credentials, separate PostgreSQL/Kafka data, and
provider calls disabled or stubbed.

## Modes

`read` is the default smoke mode. It sends a supported JSON envelope with an
ignored event type. The request still exercises TLS/reverse proxying, JSON
deserialization, secret comparison, and the credential lookup in PostgreSQL,
but it does not create durable events.

`write` sends unique `message_new` callbacks. It exercises the full webhook
acceptance path and creates transactional outbox records. Those records can
continue through Kafka and downstream services, so use this mode only in an
isolated stack. Every callback uses a unique synthetic, non-numeric user ID to
avoid real VK profile calls and unbounded conversation-history growth during a
capacity baseline.

## Isolated end-to-end staging

The repository includes a disposable Compose stack with PostgreSQL, Redis,
Kafka, all four services, and WireMock substitutes for VK and OpenRouter.
Telegram long polling is disabled. A staging-only bootstrap creates an
encrypted VK configuration, a synthetic curator, and artificial credits using
the normal application flow. A staging-only curator then routes intake to AI
and approves generated answers automatically.

Start it:

```bash
chmod +x scripts/staging-up.sh scripts/staging-down.sh
STAGING_CONFIRM=isolated-staging-only ./scripts/staging-up.sh
```

Run the default 10/25/50/100 RPS ladder:

```bash
chmod +x scripts/run-staging-load-ladder.sh
STAGING_LOAD_CONFIRM=run-isolated-staging-load \
  ./scripts/run-staging-load-ladder.sh
```

Each default step lasts 10 seconds. The runner waits for every expected
workflow to reach `COMPLETED` and for transactional outboxes to drain before
starting the next step. Override `STAGING_STEP_DURATION`, `STAGING_RATES`, or
`STAGING_DRAIN_TIMEOUT_SECONDS` for a longer server run.

Remove the complete stack and its disposable data:

```bash
STAGING_DOWN_CONFIRM=remove-isolated-staging ./scripts/staging-down.sh
```

The same scenario is available as the manually triggered `Staging load`
GitHub Actions workflow.

## Running

Set test credentials without committing them:

```bash
export LOAD_BASE_URL=https://staging.example.com
export LOAD_VK_GROUP_ID=100
export LOAD_VK_SECRET=staging_callback_secret
export LOAD_TEST_CONFIRM=non-production-load-test
```

Run the one-minute read baseline at 10 requests per second:

```bash
chmod +x scripts/run-load-test.sh
./scripts/run-load-test.sh
```

Run a write-path test at 20 accepted events per second for five minutes:

```bash
export LOAD_MODE=write
export LOAD_WRITE_CONFIRM=create-staging-events
export LOAD_RATE=20
export LOAD_DURATION=5m
export LOAD_PREALLOCATED_VUS=20
export LOAD_MAX_VUS=100
./scripts/run-load-test.sh
```

The runner uses the pinned `grafana/k6:2.0.0` image. Override
`K6_IMAGE` only as a reviewed dependency update.

## Pass criteria

The scenario fails when any of these conditions is violated:

- HTTP error rate is 1% or higher;
- fewer than 99% of checks pass;
- any scheduled iteration is dropped;
- p95 exceeds `LOAD_P95_MS`, 500 ms by default;
- p99 exceeds `LOAD_P99_MS`, 1000 ms by default.

Use `LOAD_RATE`, `LOAD_DURATION`, `LOAD_PREALLOCATED_VUS`, and `LOAD_MAX_VUS`
to define the traffic profile. Do not raise VUs merely to hide
`dropped_iterations`; they indicate either an undersized load generator or an
overloaded target.

## Test progression

1. Run `read` at 5 RPS for 1 minute as a deployment smoke test.
2. Run `read` with a stepwise series such as 10, 25, 50, and 100 RPS.
3. Run the isolated end-to-end ladder and inspect HTTP p95/p99, completion
   count, outbox drain time, Kafka lag, PostgreSQL pool usage, and DLT alerts.
4. Hold the expected rate for at least 30 minutes as a soak test.
5. Increase the rate until an SLO fails, record the first bottleneck, and stop
   before the staging host becomes unstable.

Record the commit SHA, host resources, database size, Kafka partition count,
test mode, rate, duration, thresholds, and Grafana time range with every run.
A result without this context is not a reusable capacity measurement.
