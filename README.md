# Callwise — Voice AI Diagnostic Agent

Inbound voice AI for **Sears Home Services**. The agent answers calls, identifies the appliance and symptoms, walks the caller through troubleshooting, and books a technician visit when needed.

> Take-home submission for the SHS AI Engineering Team. Built around a multi-provider LLM dispatcher (Claude Haiku 4.5 primary, Groq Llama 3.3 70B fallback) with a circuit breaker, structured observability, and a Postgres-backed scheduling layer.

---

## Quickstart

```bash
# 1. Configure secrets
cp .env.example .env
# Fill in: TWILIO_*, ANTHROPIC_API_KEY, GROQ_API_KEY, NGROK_AUTHTOKEN

# 2. Bring up the whole stack (postgres + app + ngrok tunnel)
docker compose up --build
```

That's it. `docker compose up` starts:

| Service    | What it does                                                                  | Port           |
|------------|-------------------------------------------------------------------------------|----------------|
| `postgres` | DB for sessions, conversation, scheduling, metrics                            | 5432           |
| `app`      | Spring Boot voice agent (Twilio webhook + AI dispatcher)                      | 8080           |
| `ngrok`    | Public HTTPS tunnel pointing at the app — Twilio's webhook needs this         | 4040 (UI only) |

Once healthy, point Twilio at the tunnel:

1. Twilio Console → **Phone Numbers → Active Numbers → your number**
2. **Voice Configuration → A call comes in → Webhook**
3. URL: `https://<your-ngrok-domain>/voice/incoming`  &nbsp; (HTTP POST, content-type form-encoded)
4. Status callback (optional): `https://<your-ngrok-domain>/voice/status`

Call the number. The agent picks up.

> The ngrok free tier auto-binds one persistent `*.ngrok-free.dev` domain to your account — the URL the ngrok container prints on first start (or the inspector UI at `http://localhost:4040`) keeps working across restarts. Point Twilio at it once and forget.

---

## Architecture

```
                 PSTN
                  │
                  ▼
            ┌──────────┐
            │  Twilio  │  (STT via <Gather>, TTS via <Say>)
            └────┬─────┘
                 │ HTTPS POST  (form-encoded TwiML webhook)
                 ▼
            ┌──────────┐
            │  ngrok   │  (static-domain tunnel, restart-resilient)
            └────┬─────┘
                 │
        ┌────────┴────────┐
        │  Spring Boot    │  TwilioVoiceController → ConversationOrchestrator
        │  (callwise)     │       │
        │                 │       ├─► AIDispatcher → ClaudeProvider (primary)
        │                 │       │                 GroqProvider (fallback)
        │                 │       │                 + per-provider CircuitBreaker
        │                 │       │
        │                 │       ├─► ToolRegistry → FindTechniciansTool
        │                 │       │                 ScheduleAppointmentTool
        │                 │       │                                 │
        │                 │       │                                 ▼
        │                 │       │                        SchedulingService
        │                 │       │                          (SELECT … FOR UPDATE)
        │                 │       │
        │                 │       └─► ObservabilityService (@Async → call_metrics)
        └────────┬────────┘
                 │ JDBC
                 ▼
            ┌──────────┐
            │ Postgres │  call_session, conversation_message, call_metrics,
            │   16     │  technician, service_area, specialty,
            └──────────┘  availability_slot, appointment
```

Design pattern callouts:

| Pattern               | Where                                                            |
|-----------------------|------------------------------------------------------------------|
| **Strategy**          | `ChatProvider` interface                                         |
| **Adapter**           | `ClaudeProvider`, `GroqProvider`                                 |
| **Template Method**   | `BaseProvider`                                                   |
| **Facade**            | `AIDispatcher`                                                   |
| **Circuit Breaker**   | `ai/circuit/CircuitBreaker`                                      |
| **Builder**           | `TwiMLBuilder`, `PromptBuilder`                                  |
| **Repository**        | Spring Data JPA repositories                                     |
| **Command/Registry**  | `Tool` interface + `ToolRegistry` (auto-discovered Spring beans) |
| **DTO (immutable)**   | `ai/dto/*.java` records                                          |
| **Async fire-forget** | `ObservabilityService.persistAsync` on `metricsExecutor`         |

See **DESIGN.md** for the *why* behind each major decision.

---

## Admin / Operator Endpoints

No UI by design (CLAUDE.md trade-off). Reviewer hits these with curl:

| Endpoint                                  | What it returns                                                          |
|-------------------------------------------|--------------------------------------------------------------------------|
| `GET /actuator/health`                    | Spring Boot liveness (used by docker healthcheck)                        |
| `GET /admin/health`                       | Lightweight `{"status":"UP"}` for synthetic monitoring                   |
| `GET /admin/metrics`                      | Aggregate `total_tokens`, `total_cost_usd`, by-provider rows             |
| `GET /admin/calls?limit=N`                | Recent calls (newest first) — find a `callSid` to drill into             |
| `GET /admin/calls/{callSid}`              | Session row + transcript + per-turn metrics + cost roll-up               |
| `GET /admin/appointments?limit=N`         | Recent bookings — concrete outcome of converted calls                    |
| `GET /admin/technicians`                  | Roster: each tech's ZIP coverage, appliance specialties, 14-day slot mix |
| `GET /admin/technicians/{id}/slots?days=N` | Day-by-day schedule for one tech — every slot with date/time/status      |

Example:

```bash
curl -s http://localhost:8080/admin/calls/CA1234567890abcdef | jq .
```

A ready-to-import Postman collection lives at [`postman_collection.json`](./postman_collection.json) — set the `baseUrl` and `callSid` collection variables and every endpoint above is one click away.

---

## Conversation Flow (Tier 1 + Tier 2)

1. Caller dials → `/voice/incoming` returns `<Say>` greeting + `<Gather>`.
2. Each utterance → `/voice/gather` → orchestrator loads history, calls AI dispatcher, executes any `tool_use` (max 3 iterations), persists the turn, returns TwiML `<Say>` + next `<Gather>`.
3. On caller confirmation → `SchedulingService.bookAppointment` (`SELECT … FOR UPDATE`) writes a row in `appointment`.
4. Hangup → `/voice/status` → session marked `COMPLETED`.

---

## Local Development

```bash
# Run only Postgres (for IDE-driven app runs)
docker compose up postgres

# Boot the app from your IDE or:
./gradlew bootRun

# Run tests (TestContainers spawns its own Postgres)
./gradlew test
```

`./gradlew test` produces a HTML report at `build/reports/tests/test/index.html`.

> **Note:** the two `*IT` integration tests (`SchedulingServiceIT`, `AdminControllerIT`) are `@Disabled` on macOS due to a TestContainers / Docker Desktop API quirk where the Docker daemon returns an empty body the docker-java client can't parse. They run green on Linux CI. Unit tests (20) cover the same logic — `SELECT … FOR UPDATE` semantics are still exercised at runtime by live calls. Re-enabling in CI is the next mechanical task.

---

## Project Layout (high level)

```
callwise/
├── build.gradle.kts
├── docker-compose.yml          # postgres + app + ngrok
├── Dockerfile                  # multi-stage gradle → JRE alpine
├── .env.example
├── postman_collection.json     # admin endpoints, ready to import
├── src/main/java/com/callwise/voiceagent/
│   ├── ai/                     # ChatProvider, AIDispatcher, CircuitBreaker, providers, tools, prompt
│   ├── controller/             # TwilioVoiceController, AdminController
│   ├── service/                # ConversationOrchestrator, SchedulingService, ObservabilityService, …
│   ├── entity/                 # JPA entities
│   ├── repository/             # Spring Data repositories
│   ├── config/                 # ClaudeConfig, GroqConfig, AsyncConfig
│   └── exception/              # ProviderException, GlobalExceptionHandler, …
├── src/main/resources/
│   ├── application.yml
│   ├── logback-spring.xml      # JSON encoder + MDC whitelist
│   ├── prompts/system-prompt.txt
│   └── db/changelog/           # Liquibase
└── src/test/java/…             # unit + IT tests (testcontainers + mockito)
```

---

## Submission Notes

The test phone number and live availability window are in the submission email.

For trade-offs and architecture rationale, see **DESIGN.md**.
