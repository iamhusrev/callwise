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

Shipped on this branch (`tier-3`). Five integration points:

1. **Provider abstraction extended, not bypassed.** `ChatRequest` gains an optional `images: List<ImageContent>` (default empty), where `ImageContent` is a provider-agnostic `(mediaType, base64Data)` record. `ClaudeProvider` and `GroqProvider` each detect the non-empty list, switch to their configured vision-capable model id, and attach the image to the **last user message** in their native shape — Anthropic's `{type:"image", source:{type:"base64",...}}` content block, OpenAI/Groq's `{type:"image_url", image_url:{url:"data:image/...;base64,..."}}`. Existing text-only call sites use the 5-arg `ChatRequest` constructor and see no behaviour change.
2. **`AIDispatcher.analyzeImage(bytes, mime)` reuses the chat path.** It builds a one-shot `ChatRequest` (no history, no tools, dedicated `prompts/vision-prompt.txt`) and calls `diagnose(...)` — circuit breaker, primary→fallback, observability, all free. Response is parsed against the prompt's three-line contract into a `VisionResult(applianceType, visibleIssues, suggestedNextStep, rawText, providerName)`.
3. **`image_upload` table** (Liquibase 005) tracks the lifecycle: `PENDING → UPLOADED → ANALYZED | FAILED | EXPIRED`. `vision_result` is JSONB so the admin endpoints can pluck specific fields without a deserialisation round-trip. Indexed on `token` (public lookup) and `call_session_id` (per-call latest).
4. **Two new function-calling tools** auto-discover via `ToolRegistry`. `request_image_upload({email, reason})` validates the email, creates the row, sends the link via `EmailService` (SMTP → MailHog locally), returns `{status:"sent", expires_in_minutes:30}`. `check_image_status({})` reads the latest `image_upload` row for the active session and returns `{status, vision: {appliance_type, visible_issues, suggested_next_step}, vision_provider}` once analysis completes — the AI weaves it into its next response. The system prompt was extended with one paragraph of guidance on when to use them; no orchestrator changes were needed.
5. **Public upload endpoint** (`UploadController`, Thymeleaf views) at `/uploads/{token}`. Token is 24 random bytes from `SecureRandom`, base64-url-encoded — 192 bits of entropy. Single-use, 30-min TTL. MIME allow-list (`image/jpeg|png|webp`) + size cap enforced both at Spring multipart and inside `ImageUploadService`. After write, the service fires `analyzeAsync` on a dedicated `visionExecutor` pool (separate from `metricsExecutor` because vision calls take 1-5 s and shouldn't queue behind sub-millisecond metric inserts).

### Trade-offs taken on Tier 3

- **MailHog over SendGrid/SES.** The take-home runs locally; reviewers shouldn't need a third-party account. MailHog ships in compose, web UI on `:8025`, zero external dependency. Switching to a real SMTP provider is one `application.yml` change — `JavaMailSender` is already provider-agnostic.
- **Local bind-mounted `./uploads/` over S3 / Supabase Storage.** Same reason: keep the demo self-contained. The path is configurable; production swap is changing `UPLOAD_DIR` (and rewriting the file-write block to a `S3Client.putObject` if needed). DB BYTEA was rejected — it would inflate the Postgres footprint and isn't a typical JPA pattern.
- **Multi-provider Vision over single-provider.** Same `ChatProvider` abstraction the chat path uses. Anthropic outage during a call would otherwise leave Tier 3 dark. Groq's vision lineup is volatile, so the model id is configurable (`VISION_GROQ_MODEL`) instead of hard-coded.
- **Polling tool over outbound Twilio call.** PDF said "send a link"; outbound call-back when the photo arrives is a nice-to-have and out of scope. The AI calls `check_image_status` on subsequent turns and gracefully degrades if the caller never uploads.
- **Token-based URL over signed S3 URL.** No S3, so signed URLs aren't applicable; the random token is the secret. 192 bits + 30-min TTL + single-use is overkill for the threat model and that's intentional.

### Out of scope on Tier 3 (deliberately)

- **Image content moderation / virus scan.** Production would add a ClamAV step before vision. We accept the photo, scan never runs.
- **PII retention policy.** Uploads sit on the bind mount until manually cleared. Production needs a TTL job + an audit trail of who viewed what.
- **Outbound re-engagement call** when an upload arrives after hangup. Polling-only by design.
- **Multi-image per token.** One image per upload row; the AI can request a second link if needed.

