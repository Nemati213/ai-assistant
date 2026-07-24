# Production Runbook

## Server prerequisites

- Linux server with Docker Engine and Docker Compose v2.
- DNS `A` record for `PUBLIC_DOMAIN` pointing to the server.
- Inbound TCP ports 80 and 443 and UDP port 443 open.
- At least 4 CPU cores, 8 GB RAM, and 40 GB persistent disk for the initial load.

The current Compose topology is a reliable single-host deployment, not a
high-availability Kafka or PostgreSQL cluster.

## First deployment

1. Copy `.env.prod.example` to `.env.prod`.
2. Replace every placeholder. Every database service must have a different
   password. Use URL-safe characters for `REDIS_PASSWORD` because it is
   embedded into a Redis URI.
3. Restrict access to the secrets file:

   ```bash
   chmod 600 .env.prod
   ```

4. Generate the encryption key:

   ```bash
   openssl rand -base64 32
   ```

5. Validate configuration and secrets:

   ```bash
   chmod +x scripts/preflight-prod.sh
   ./scripts/preflight-prod.sh
   ```

6. Build and start:

   ```bash
   docker compose --env-file .env.prod -f compose.prod.yml up -d --build
   ```

7. Check containers and application health:

   ```bash
   docker compose --env-file .env.prod -f compose.prod.yml ps
   docker compose --env-file .env.prod -f compose.prod.yml logs --tail=200
   ```

8. Configure VK Callback API with:

   ```text
   https://PUBLIC_DOMAIN/vk/webhook
   ```

Only `/vk/webhook` is publicly proxied. Actuator endpoints stay inside the
Docker network.

## Updating

Run the test suite before deployment. On Windows, use:

```bat
scripts\test-windows.bat
```

Then deploy:

```bash
docker compose --env-file .env.prod -f compose.prod.yml build
docker compose --env-file .env.prod -f compose.prod.yml up -d
```

Flyway applies database migrations on service startup. Never edit an already
applied migration; add a new versioned migration.

## Backups

Create a PostgreSQL backup:

```bash
chmod +x scripts/backup-postgres.sh
./scripts/backup-postgres.sh
```

Schedule it daily with cron and copy backups to storage outside the server.
The default local retention is 14 days.

Test restoration on a separate environment before accepting real payments.

## Operations

View logs:

```bash
docker compose --env-file .env.prod -f compose.prod.yml logs -f \
  vk-connector-service tg-connector-service orchestrator-service ai-service
```

Kafka UI is disabled by default. To expose it only on server localhost:

```bash
docker compose --env-file .env.prod -f compose.prod.yml \
  --profile ops up -d kafka-ui
```

Access it through an SSH tunnel, never by opening port 8080 publicly.

## Required external checks before launch

- Complete one real Telegram Stars payment and verify idempotent crediting.
- Complete manual and AI answer workflows with text and photos.
- Simulate OpenRouter, VK, Telegram, Kafka, and PostgreSQL restarts.
- Verify automatic VK retry and AI-answer refund behavior.
- Verify the admin allowlist and manual credit audit.
- Confirm backup creation and restoration.
