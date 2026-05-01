# CLAUDE.md — Project Rules for Callwise

> Project-wide rules and conventions Claude Code must follow. Loaded automatically every session.

---

## Project Context

**Project:** Callwise — voice AI diagnostic agent (Sears Home Services backend engineer take-home).

**Stack:**
- Spring Boot 3.5 + Java 21 (monolithic, deliberate)
- PostgreSQL 16 + Liquibase migrations
- Twilio (telephony with built-in `<Gather>` STT, `<Say>` TTS)
- Claude Haiku 4.5 (primary AI)
- Groq Llama 3.3 70B (fallback AI)
- Docker Compose for deployment

**Architecture:** Single Spring Boot service with multi-provider AI abstraction. Production-grade observability via structured logs and admin endpoints. No UI by design.

> Note: The product user-facing strings (greetings, prompt) say "Sears Home Services" because that is the use-case persona. The repo, package, and DB are named **callwise**. Do not mix.

---

## Code Conventions

### Java Style
- Use **Java 21** features: records, sealed classes, pattern matching, virtual threads where applicable
- Prefer **records** for DTOs (immutable, less boilerplate)
- Use **Lombok** sparingly — only for entities (`@Data`, `@NoArgsConstructor`, `@AllArgsConstructor`)
- Constructor injection always (no `@Autowired` on fields)
- All public methods have **JavaDoc** for non-trivial logic
- **Comments in English only** — no Turkish, no other languages
- Use `var` only when type is obvious from RHS

### Package Structure
```
com.callwise.voiceagent
├── controller/    # REST endpoints
├── service/       # Business logic
├── ai/            # AI providers, prompts, tools
│   ├── provider/  # ChatProvider implementations
│   ├── prompt/    # Prompt building
│   ├── tools/     # Function calling tools
│   ├── dto/       # AI request/response DTOs
│   └── circuit/   # Circuit breaker
├── entity/        # JPA entities
├── repository/    # Spring Data JPA
├── config/        # @Configuration classes
└── exception/     # Custom exceptions + handlers
```

### Naming
- Controllers end with `Controller`
- Services end with `Service`
- Repositories end with `Repository`
- DTOs are records named like `ChatRequest`, `ToolCall`
- Entities are JPA entities matching DB tables (singular, e.g., `Technician`)

---

## Hard Rules — NEVER Violate

1. **Never put secrets in code** — always use `${ENV_VAR}` from `application.yml`
2. **Never commit `.env`** — only `.env.example`
3. **Never log API keys, full prompts, or full conversation content at INFO level** — use DEBUG
4. **Never skip Liquibase** — all schema changes go through migrations
5. **Never use field injection (`@Autowired` on field)** — constructor injection only
6. **Never write tests in production directory** — `src/test/java/...` only
7. **Never use `@Disabled` on tests without a TODO with date and reason**
8. **Never call AI providers from controllers directly** — go through `AIDispatcher`

---

## Testing Requirements

- **Every service method** must have at least one unit test
- **Every controller endpoint** must have an integration test
- **AI provider implementations** must use WireMock for HTTP mocking
- **Database tests** must use TestContainers (real Postgres, not H2)
- **Coverage target:** >70% for `service/` and `ai/` packages
- **Test naming:** `methodName_givenCondition_expectedOutcome` pattern
- **No flaky tests** — if a test fails intermittently, fix it or delete it

---

## Verification Before Completion

Before marking any task complete:

1. Code compiles (`./gradlew compileJava`)
2. Tests pass (`./gradlew test`)
3. No TODO comments left without explanation
4. Logs at appropriate levels (DEBUG for verbose, INFO for events, ERROR for failures)
5. New code has tests
6. If config added, also added to `application.yml` AND `.env.example`

---

## AI Integration Rules

### Provider Abstraction
- All AI calls go through `ChatProvider` interface
- Adding a new provider = implement `ChatProvider`, register in `AIDispatcher`
- Never hardcode provider-specific logic outside `provider/` package
- DTOs are provider-agnostic; mapping happens inside provider implementation

### Prompt Engineering
- System prompts live in `src/main/resources/prompts/*.txt`
- **Never hardcode prompts in Java code** — load from files
- Test prompt changes by running diagnostic conversations
- Document significant prompt changes in commit messages

### Tool Use (Function Calling)
- Tools implement `Tool` interface
- Tool execution goes through `ToolRegistry`
- Tools are stateless — don't store request data in tool fields
- Tool results returned as JSON strings
- Maximum 3 tool iterations per turn (prevent infinite loops)

### Cost Awareness
- Every AI call tracks tokens via `ChatResponse.inputTokens` / `outputTokens`
- `ObservabilityService.recordTurn()` calculates cost using current pricing
- Update pricing constants in `ObservabilityService` when providers change rates

---

## Observability Standards

### Logging
- Use **SLF4J + Logback** with JSON encoder (logstash-logback-encoder)
- **MDC fields:** `callSid`, `turnNumber`, `provider`
- Log events with structured fields, not string concatenation:
  ```java
  // GOOD
  log.info("ai.response", kv("provider", "claude"), kv("latency_ms", 850));

  // BAD
  log.info("Claude responded in 850ms");
  ```

### Metrics Persistence
- Every AI call → save row to `call_metrics` table
- Use `@Async` to avoid blocking voice response path
- Metrics: latency, tokens, cost, success/failure, provider used

---

## Database Conventions

### Migrations
- All schema changes via Liquibase YAML changelogs
- Migrations are **immutable** — never edit deployed changesets
- Add new changeset for fixes
- Filename pattern: `NNN-description.yaml` (e.g., `005-add-call-metrics-index.yaml`)

### Entities
- All entities have `created_at`, `updated_at` timestamps
- Use `@PrePersist`, `@PreUpdate` for auto-population
- Multi-tenant pattern: lead with `call_session_id` for indexes when relevant
- Use `BIGSERIAL` for primary keys (matches Liquibase generated)

### Queries
- Prefer **derived queries** for simple cases (`findByCallSid`)
- Use `@Query` JPQL for complex joins
- Native SQL only when JPQL can't express it (rare)
- Always use `LEFT JOIN FETCH` to avoid N+1 problems

---

## Twilio Conventions

### Webhook Endpoints
- All endpoints accept `application/x-www-form-urlencoded`
- All endpoints return `application/xml` (TwiML)
- Use `TwiMLBuilder` service — never construct TwiML manually with strings
- Always escape user content in TwiML output (XML special chars)

### TwiML Best Practices
- Always include `<Gather>` after `<Say>` (or call ends abruptly)
- Set `timeout="5"` for natural pause tolerance
- Default Twilio voice (no Polly upgrade for take-home cost reasons; switching is one-line)
- Always have a fallback `<Say>` after `<Gather>` for silence handling

---

## Resilience Patterns

### Circuit Breaker
- All AI provider calls go through `CircuitBreaker`
- 3 consecutive failures → OPEN
- 30 seconds in OPEN → HALF_OPEN
- Success in HALF_OPEN → CLOSED
- Failure in HALF_OPEN → OPEN (reset timer)

### Timeouts
- AI provider HTTP calls: 8 seconds
- Database queries: 5 seconds
- Twilio webhook responses: must complete in <10 seconds (Twilio limit)

### Fallback Behavior
- Primary AI fails → try fallback AI
- All AI fails → return graceful TwiML: "I'm having trouble. Please call back."
- Database fails → return graceful TwiML, mark session FAILED
- Tool execution fails → continue conversation, AI sees error result

---

## Anti-Patterns to Avoid

- Don't use `RestTemplate` — use `RestClient` (Spring 6+)
- Don't use `WebFlux` — this is a sync application
- Don't use `H2` in tests — use TestContainers
- Don't use `JpaSpecificationExecutor` for simple queries
- Don't create custom exceptions for every error — use existing where possible
- Don't add libraries without justification — every dependency is reviewed
- Don't generate placeholder code — implement fully or skip
- Don't suppress warnings without explanation comment

---

## Documentation Standards

### When You Modify Code
- Update relevant JavaDoc if signature changes
- Update README.md if user-facing behavior changes
- Update DESIGN.md if architecture decision changes
- Add migration notes if breaking changes

### Comment Quality
- **Why, not what** — code shows what; comments explain why
- Mark non-obvious choices: `// Using FOR UPDATE to prevent double booking`
- Mark TODOs with date and owner: `// TODO 2026-04-29: Add retry logic`

---

## Trade-offs Made (For Code Review)

These are deliberate decisions — don't "improve" without discussion:

1. **Monolith over microservices** — pragmatic for take-home scope
2. **Twilio built-in STT/TTS** over Deepgram/ElevenLabs — latency wins for voice
3. **Postgres** for state over Redis — durability > latency for state ops
4. **Synchronous Spring** over WebFlux — simpler debugging, single-threaded calls
5. **No UI** — admin endpoints + structured logs is production-grade observability
6. **Multi-provider AI** with abstraction — single-vendor lock-in is unacceptable
7. **In-memory circuit breaker** — would use Resilience4j at production scale

---

## Workflow Triggers

When you (Claude Code) detect:

- **New AI provider needed** → Use the `add-provider` skill
- **New tool/function needed** → Use the `add-tool` skill
- **Schema change needed** → Use the `add-migration` skill
- **API contract testing** → Use the `test-coverage` skill
- **Code review needed** → Delegate to `code-reviewer` subagent
- **Integration tests passing check** → Delegate to `integration-test-runner` subagent

---

## Final Reminder

This project is being evaluated by Santiago Fonseca, Head of Agentic AI at Sears Home Services.

He values:
- Working software over perfect software
- Pragmatic engineering — use existing tools
- Clear communication — code should explain itself
- Attention to user experience — voice latency matters

Every decision should pass the test: **"Could I defend this in code review?"**

If unsure, prefer the simpler, more boring solution. Production engineers ship boring code.
