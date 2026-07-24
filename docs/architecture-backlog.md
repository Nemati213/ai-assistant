# Architecture Backlog

## Student context and conversation history

Future AI context must be owned by a dedicated student-context component and
stored in its own PostgreSQL database, tentatively named `student_context_db`.

The data model should distinguish:

- `students`: tenant-aware student identity keyed by VK group and VK user.
- `conversations`: a dialogue in a VK peer/chat.
- `messages`: ordered student, assistant, and curator messages with timestamps.
- `conversation_summaries`: compact summaries used when the raw sliding window
  no longer fits the model context.

Required constraints:

- unique student identity by `(vk_group_id, vk_user_id)`;
- index messages by `(conversation_id, created_at)`;
- preserve `request_id` for idempotency and tracing;
- configurable retention and deletion for personal data;
- AI service receives only the last N messages plus an optional summary;
- no direct database access from other services: exchange context events or
  commands through Kafka.

This is intentionally deferred until the delivery pipeline, retries, and
billing are reliable.

The AI service now owns an idempotent PostgreSQL request journal. A request is
claimed before the provider call, terminal results are published from stored
state with retries, and stale in-flight requests fail without issuing a second
potentially billable provider call.

## AI provider token pool

Future AI traffic must support multiple provider credentials instead of one
static OpenRouter token.

- Store secret values in Vault, Kubernetes Secrets, or another secret manager.
- Store only secret references and operational metadata in the service database.
- Metadata should include provider, model, priority, weight, rate limits,
  enabled state, cooldown deadline, and recent failure/load counters.
- Route requests with weighted least-loaded selection.
- Temporarily remove credentials from rotation after rate-limit or provider
  failures, then probe them again after cooldown.
- Keep request assignment idempotent so Kafka retries do not charge or execute
  the same generation through multiple credentials.

## Administrative Telegram bot

A separate administrative bot will provide controlled operational actions:

- credit curator token balances after verified off-platform payments;
- ban and unban curators, users, or VK groups;
- inspect curator, group, balance, and recent workflow status;
- record the administrator identity, reason, timestamp, and idempotency key for
  every action;
- protect access with an administrator allowlist or role model;
- require all balance changes to go through an immutable billing ledger rather
  than directly updating the current balance.

The billing ledger, Telegram Stars payment flow, administrator allowlist,
curator inspection, aggregate statistics, manual crediting, idempotency, and
the immutable `admin_actions` audit are implemented. Ban/unban, manual debit,
workflow inspection, and broadcasts remain deferred.

## Current credit pricing

AI usage is charged from the provider-reported OpenRouter `usage.cost`, not raw
token count:

`credits = max(minimum_charge, ceil(provider_cost_usd * credits_per_usd))`

Current defaults:

- `credits_per_usd`: 200,000;
- `minimum_charge`: 100 credits;
- Telegram Stars Pro package: 350 Stars for 300,000 credits.

The provider cost, token count, pricing rate, minimum charge, and final charged
credits are stored in the billing ledger for auditability. Both orchestrator
and TG connector recalculate the charge and reject a pricing mismatch.
