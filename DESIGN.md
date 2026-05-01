# Callwise — Design Document

A short, opinionated walkthrough of the architectural choices and the trade-offs behind them. The audience is a future code reviewer who wants to know *why* — the *what* lives in code and JavaDoc.

## Goals

Tier 1 + Tier 2 of the SHS take-home, end-to-end: an inbound call agent that diagnoses appliance issues, walks the caller through troubleshooting, and books a technician visit. **Tier 3 (visual diagnosis) is being built on the `tier-3` branch** — design rationale lives in that branch's DESIGN.md and a follow-up email will cover it.

Implicit non-goals on `main`: production-grade auth, horizontal scale, custom CV models, Polly-grade voice prosody. Each is called out below where it changed a decision.

## Stack at a Glance

| Layer        | Choice                                | Why this over alternatives                                                                                                              |
|--------------|---------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| Telephony    | **Twilio** with built-in `<Gather>`   | Ubiquitous webhook model; STT and TTS are free with the call — no extra round-trip latency. Switching to Deepgram/ElevenLabs is a future swap. |
| LLM primary  | **Claude Haiku 4.5**                  | Voice latency dominates UX; Haiku is fast, supports native `tool_use`, and Anthropic's prompt caching makes the system prompt cheap.    |
| LLM fallback | **Groq Llama 3.3 70B**                | LPU inference (~500 tok/s) and provider diversity. If Anthropic has an outage, the call still completes.                                |
| Backend      | **Spring Boot 3.5 + Java 21 (sync)**  | Plays to my strength; voice latency is dominated by AI not I/O, so reactive buys little while costing debug ergonomics.                 |
| Build        | **Gradle Kotlin DSL**                 | Type-safe build script with first-class IDE support; one-line override for the testcontainers / docker-java versions when needed.       |
| DB           | **PostgreSQL 16**                     | `SELECT … FOR UPDATE` for safe slot booking; durable session/transcript storage; rich SQL for admin reports.                            |
| Migrations   | **Liquibase**                         | YAML, declarative, immutable changesets, native rollback support.                                                                       |
| Tunnel       | **ngrok (auto-bound free domain)**    | Twilio webhook needs a public HTTPS URL. The free tier binds one persistent `*.ngrok-free.dev` domain per account, so the webhook URL is set once and survives `docker compose down/up`. |
| Container    | **Docker Compose**                    | The deliverable says "single command"; compose makes that literal (postgres + app + tunnel together).                                   |
| Logging      | **Logback + logstash-logback-encoder** | JSON output ELK/Loki/Datadog parse natively; MDC carries `callSid`/`turnNumber`/`provider` so a single grep surfaces a whole call.      |
| Tests        | **JUnit 5 + TestContainers + Mockito** | Real Postgres in tests catches migration drift H2 hides; Mockito for fast unit isolation.                                               |

## Multi-provider AI

`ChatProvider` is the seam (Strategy). `ClaudeProvider` and `GroqProvider` (Adapter) translate the same `ChatRequest`/`ChatResponse` records into each vendor's wire format; `BaseProvider` is a thin shared helper for nanosecond-accurate latency timing. Adding a third provider means writing the wire-format adapter and wiring it into `AIDispatcher` — no callsite changes elsewhere.

`AIDispatcher` (Facade) owns the primary → fallback decision and the circuit-breaker bookkeeping: every call goes through `circuitBreaker.allowRequest(name)` before hitting a provider, every outcome calls `recordSuccess`/`recordFailure`, and each state transition emits a structured log event (`dispatcher.primary-circuit-open`, `dispatcher.primary-failed`, `dispatcher.fallback-circuit-open`, `dispatcher.fallback-failed`). When both providers are unavailable, `AllProvidersFailedException` is raised; `GlobalExceptionHandler` translates it into a graceful TwiML hangup so Twilio plays the apology and ends the call cleanly instead of dropping on non-XML.

`CircuitBreaker` is in-memory and per-provider — `FAILURE_THRESHOLD = 3`, `OPEN_DURATION = 30s`, single-probe HALF_OPEN, hand-rolled instead of pulling Resilience4j. The trade-off is explicit: per-instance state is fine for a single-pod take-home; a multi-replica deployment would migrate to Resilience4j with shared state.

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

Twilio drops the call if the webhook doesn't return inside ~10 s. AI HTTP timeout is 8 s, DB reads/writes typically <50 ms, `@Async` metrics persist is off the critical path, and the circuit breaker fail-fasts when a provider is OPEN. Worst case (both providers down), `GlobalExceptionHandler` returns a "please call back" TwiML so Twilio hangs up gracefully instead of dropping the line.

## Out of Scope (deferred deliberately)

- **Auth on `/admin/*`** — Spring Security + API key in production; take-home reviewability wins here.
- **Outbound calls** — agent only receives. Reminders / re-engagement are a Twilio Calls API away.
- **Distributed tracing** — `callSid` in MDC already gives per-call tracing; OpenTelemetry would be the next step at scale.

## Tier 3 (Visual Diagnosis)

Implemented on the `tier-3` branch — `Message` carries an optional `imageUrls` field, `ClaudeProvider` emits content blocks when images are present, two new tools (`request_image`, `poll_image_status`) auto-discover into `ToolRegistry` with zero orchestrator changes, and a new Liquibase changeset adds the `image_uploads` table with token + email + JSONB vision result. Branch's own DESIGN.md walks through the upload flow, vision JSON schema, and TTL/cleanup choices; the follow-up email will link it and the merged PR.

