# Callwise — Design Document

A short, opinionated walkthrough of the architectural choices and the trade-offs behind them. The audience is a future code reviewer who wants to know *why* — the *what* lives in code and JavaDoc.

## Goals

Tier 1 + Tier 2 of the SHS take-home, end-to-end: an inbound call agent that diagnoses appliance issues, walks the caller through troubleshooting, and books a technician visit. Tier 3 (visual diagnosis) is intentionally out of scope for the initial submission; the architecture leaves a clear seam for it (see *Tier 3 readiness* below).

Implicit non-goals: production-grade auth, horizontal scale, custom CV models, Polly-grade voice prosody. Each is called out below where it changed a decision.

## Stack at a Glance

| Layer        | Choice                                | Why this over alternatives                                                                                                              |
|--------------|---------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| Telephony    | **Twilio** with built-in `<Gather>`   | Ubiquitous webhook model; STT and TTS are free with the call — no extra round-trip latency. Switching to Deepgram/ElevenLabs is a future swap. |
| LLM primary  | **Claude Haiku 4.5**                  | Voice latency dominates UX; Haiku is fast, supports native `tool_use`, and Anthropic's prompt caching makes the system prompt cheap.    |
| LLM fallback | **Groq Llama 3.3 70B**                | LPU inference (~500 tok/s) and provider diversity. If Anthropic has an outage, the call still completes.                                |
| Backend      | **Spring Boot 3.5 + Java 21 (sync)**  | Plays to my strength; voice latency is dominated by AI not I/O, so reactive buys little while costing debug ergonomics.                 |
| Build        | **Gradle Kotlin DSL**                 | Type-safe, modern. Migrated from Maven for a cleaner build script.                                                                      |
| DB           | **PostgreSQL 16**                     | `SELECT … FOR UPDATE` for safe slot booking; durable session/transcript storage; rich SQL for admin reports.                            |
| Migrations   | **Liquibase**                         | YAML, declarative, immutable changesets, native rollback support.                                                                       |
| Tunnel       | **ngrok (auto-bound free domain)**    | Twilio webhook needs a public HTTPS URL. The free tier binds one persistent `*.ngrok-free.dev` domain per account, so the webhook URL is set once and survives `docker compose down/up`. |
| Container    | **Docker Compose**                    | The deliverable says "single command"; compose makes that literal (postgres + app + tunnel together).                                   |
| Logging      | **Logback + logstash-logback-encoder** | JSON output ELK/Loki/Datadog parse natively; MDC carries `callSid`/`turnNumber`/`provider` so a single grep surfaces a whole call.      |
| Tests        | **JUnit 5 + TestContainers + Mockito** | Real Postgres in tests catches migration drift H2 hides; Mockito for fast unit isolation.                                               |

## Multi-provider AI

`ChatProvider` is the seam (Strategy). `BaseProvider` (Template Method) wraps every concrete provider with the same circuit-breaker check and latency timing — so adding a third provider means writing the wire-format adapter and registering it in `AIDispatcher`. CLAUDE.md has this as a hard rule because vendor lock-in on the LLM is unacceptable in production.

The dispatcher (`AIDispatcher`) is a Facade: callers see one method, internally it does primary → fallback with explicit logging events for each transition. Failure is communicated via `AllProvidersFailedException`, which `GlobalExceptionHandler` turns into graceful TwiML so Twilio doesn't drop the call on a non-XML response.

`CircuitBreaker` is in-memory and per-provider. The state machine (CLOSED → 3 failures → OPEN → 30 s → HALF_OPEN) is implemented in ~50 lines of state, instead of pulling Resilience4j. Production deployments would swap to Resilience4j; this is an explicit scope-limited choice.

## Conversation State

`call_session` + `conversation_message` tables are durable so the AI's "do not ask for information already provided" requirement holds across `<Gather>` round-trips and even Spring restarts mid-call. Each turn is its own row with a `turn_number` and a discriminator role (`USER`, `ASSISTANT`, `TOOL_CALL`, `TOOL_RESULT`) — that's enough to reconstruct the prompt for the next AI call without storing the messages JSON-blob style.

`PromptBuilder` loads the system prompt from `prompts/system-prompt.txt` (CLAUDE.md hard rule: no inline prompts in Java) and rebuilds the message list deterministically from the persisted history.

## Scheduling and Concurrency

`SchedulingService.bookAppointment` runs in a `REQUIRES_NEW` + `REPEATABLE_READ` transaction and grabs the slot row with `SELECT … FOR UPDATE`. Two callers racing for the same slot serialise on the row lock; only one wins, the loser sees `SlotNotAvailableException`. The tool layer turns the exception into a structured error envelope so the AI can offer the next slot rather than crash the call.

Why pessimistic over optimistic: the cost of "tell the customer to wait while we retry" outweighs the rare-conflict efficiency win. Fail fast, present alternatives.

## Tools (Function Calling)

`Tool` interface + `ToolRegistry` autodiscovers Spring beans, so adding a tool is a one-class change with zero orchestrator wiring. `ToolContext` (ThreadLocal) carries `callSid` and `sessionId` into the tool without polluting the `Tool#execute` signature — important because the AI's schema must contain only fields the customer can actually answer for.

Tool loop is bounded at `MAX_TOOL_ITERATIONS=3` to defend against infinite tool-call loops. Three is sufficient for the find → confirm → book sequence; higher caps would just mask AI prompt issues.

## Observability

No UI by design — CLAUDE.md trade-off. Three pieces:

1. **Structured logs** (`logback-spring.xml`): JSON via `LogstashEncoder`, MDC keys `callSid`/`turnNumber`/`provider`. A reviewer can `docker compose logs app | jq 'select(.callSid == "CA…")'` to read a whole call.
2. **`call_metrics`** table written by `ObservabilityService` on a dedicated `metricsExecutor` (`@Async` fire-and-forget) — keeps the 50 ms DB write off Twilio's 10 s webhook budget. Cost is computed inline from a per-provider rate card; one place to update when prices change.
3. **`/admin/*` endpoints**: read-only views over the call + metrics tables. Per-call detail and global aggregates. Acts as the operator UI without the operator UI.

## Resilience Budget

Twilio drops the call if the webhook doesn't return inside ~10 s. The budget:

- AI HTTP timeout: **8 s** (set in `application.yml`)
- DB queries (read history, write turn): typically <50 ms
- `@Async` metrics insert is off the critical path
- Circuit breaker fail-fast: when OPEN, dispatcher returns immediately to the fallback path

Worst case (both providers down), `GlobalExceptionHandler` returns a "please call back" TwiML — Twilio plays it and hangs up gracefully.

## Out of Scope (and why)

- **Tier 3 visual diagnosis** — the planning doc has the design (new `image_upload` table, `RequestImageTool`, Claude Vision via `ChatRequest.imageUrls`), but implementation is queued. Not blocking the take-home.
- **Auth on `/admin/*`** — would be Spring Security + an API key in production. Take-home scope.
- **Outbound calls** — the agent only receives. Outbound (e.g., reminding a customer their appointment was booked) is a Twilio Calls API call away.
- **Custom CV / Polly-grade voice** — both are one-line config changes. Defer until the basic UX is right.
- **Distributed tracing** — `callSid` MDC effectively gives per-call tracing; no need for OpenTelemetry yet.

## Tier 3 Readiness

The architecture has the seams already:

- `ChatRequest` can grow an `imageUrls` field without breaking either provider.
- A new `RequestImageTool` registered in `ToolRegistry` requires zero orchestrator changes (Plugin pattern).
- A new Liquibase changeset adds `image_upload` (id, call_session_id, token, email, status, image_path, vision_result, expires_at).
- A `PUBLIC_BASE_URL` env var would carry the upload-link host into the tool — trivial addition.

Estimated 1 focused day to build Tier 3 on top of the current shape.

## Things I'd Change at Production Scale

- Resilience4j replaces the in-memory circuit breaker (per-instance state isn't ideal across replicas).
- Spring Security on `/admin/*` + per-API-key rate limiting.
- Per-tenant data isolation if Sears wanted multi-line-of-business deployments — `call_session_id` is already the lead column on indexes, so the sharding path is straightforward.
- A `prompt-version` column on `call_metrics` for A/B testing prompt changes against cost/quality.
- Switch Twilio's `<Say>` voice to Polly (one-line config) for better prosody.
