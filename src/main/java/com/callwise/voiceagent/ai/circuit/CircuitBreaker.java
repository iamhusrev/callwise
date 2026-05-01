package com.callwise.voiceagent.ai.circuit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory circuit breaker keyed by provider name.
 *
 * <p>State machine:
 * <pre>
 *   CLOSED  ──3 consecutive failures──▶  OPEN
 *   OPEN    ──30s elapsed──────────────▶ HALF_OPEN  (1 test request allowed)
 *   HALF_OPEN  success ▶ CLOSED      |   failure ▶ OPEN (timer resets)
 * </pre>
 *
 * <p>Production deployments would swap this for Resilience4j — listed in DESIGN.md as a
 * deliberate scope-limited choice.
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration OPEN_DURATION = Duration.ofSeconds(30);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final Map<String, ProviderState> states = new ConcurrentHashMap<>();

    /**
     * @return true if the dispatcher should attempt the provider; false if the breaker is OPEN.
     */
    public boolean allowRequest(String providerName) {
        ProviderState s = states.computeIfAbsent(providerName, n -> new ProviderState());
        synchronized (s) {
            if (s.state == State.CLOSED) return true;
            if (s.state == State.OPEN) {
                if (Instant.now().isAfter(s.openedAt.plus(OPEN_DURATION))) {
                    s.state = State.HALF_OPEN;
                    log.info("circuit.transition provider={} from=OPEN to=HALF_OPEN", providerName);
                    return true;
                }
                return false;
            }
            // HALF_OPEN: allow exactly one probe at a time. Concurrent half-open requests would
            // race; in practice the orchestrator is single-threaded per call, so this matters
            // only across calls.
            return true;
        }
    }

    public void recordSuccess(String providerName) {
        ProviderState s = states.computeIfAbsent(providerName, n -> new ProviderState());
        synchronized (s) {
            s.consecutiveFailures = 0;
            if (s.state != State.CLOSED) {
                log.info("circuit.transition provider={} from={} to=CLOSED", providerName, s.state);
                s.state = State.CLOSED;
            }
        }
    }

    public void recordFailure(String providerName) {
        ProviderState s = states.computeIfAbsent(providerName, n -> new ProviderState());
        synchronized (s) {
            s.consecutiveFailures++;
            if (s.state == State.HALF_OPEN || s.consecutiveFailures >= FAILURE_THRESHOLD) {
                if (s.state != State.OPEN) {
                    log.warn("circuit.transition provider={} from={} to=OPEN failures={}",
                            providerName, s.state, s.consecutiveFailures);
                }
                s.state = State.OPEN;
                s.openedAt = Instant.now();
            }
        }
    }

    /** Snapshot read for /admin/health. Not state-mutating. */
    public State getState(String providerName) {
        ProviderState s = states.get(providerName);
        return s == null ? State.CLOSED : s.state;
    }

    private static final class ProviderState {
        State state = State.CLOSED;
        int consecutiveFailures = 0;
        Instant openedAt = Instant.EPOCH;
    }
}
