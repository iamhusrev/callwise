package com.callwise.voiceagent.ai;

import com.callwise.voiceagent.ai.circuit.CircuitBreaker;
import com.callwise.voiceagent.ai.dto.ChatRequest;
import com.callwise.voiceagent.ai.dto.ChatResponse;
import com.callwise.voiceagent.ai.provider.ClaudeProvider;
import com.callwise.voiceagent.ai.provider.GroqProvider;
import com.callwise.voiceagent.exception.AllProvidersFailedException;
import com.callwise.voiceagent.exception.ProviderException;
import com.callwise.voiceagent.service.ObservabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Routes chat requests through a primary/fallback provider chain, gated by per-provider
 * circuit breakers. Currently {@link ClaudeProvider} primary, {@link GroqProvider} fallback.
 *
 * <p>Adding a third provider is a matter of adding a constructor argument and another arm
 * to {@link #diagnose}. Phase 5 sees the dispatcher emit structured fallback events for the
 * admin metrics endpoint.
 */
@Service
public class AIDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AIDispatcher.class);

    private final ChatProvider primary;
    private final ChatProvider fallback;
    private final CircuitBreaker circuitBreaker;
    private final ObservabilityService observability;

    public AIDispatcher(
            ClaudeProvider primary,
            GroqProvider fallback,
            CircuitBreaker circuitBreaker,
            ObservabilityService observability
    ) {
        this.primary = primary;
        this.fallback = fallback;
        this.circuitBreaker = circuitBreaker;
        this.observability = observability;
    }

    /**
     * Try primary; on circuit-open or failure, try fallback. If both fail, raise
     * {@link AllProvidersFailedException} so the caller can return a graceful TwiML.
     */
    public ChatResponse diagnose(ChatRequest request) {
        ProviderException primaryError = null;

        if (circuitBreaker.allowRequest(primary.getName())) {
            try {
                ChatResponse r = primary.chat(request);
                circuitBreaker.recordSuccess(primary.getName());
                observability.recordProviderSuccess(primary.getName(), r.latencyMs(), r.inputTokens(), r.outputTokens());
                return r;
            } catch (ProviderException e) {
                circuitBreaker.recordFailure(primary.getName());
                observability.recordProviderFailure(primary.getName(), 0, e.getMessage());
                primaryError = e;
                log.warn("dispatcher.primary-failed provider={} status={} msg={}",
                        e.getProviderName(), e.getStatusCode(), e.getMessage());
            }
        } else {
            log.info("dispatcher.primary-circuit-open provider={}", primary.getName());
        }

        // Fallback path
        if (!circuitBreaker.allowRequest(fallback.getName())) {
            log.error("dispatcher.fallback-circuit-open provider={}", fallback.getName());
            throw new AllProvidersFailedException(
                    "Both providers are unavailable (primary=" + primary.getName()
                            + ", fallback=" + fallback.getName() + " circuit open)",
                    primaryError
            );
        }

        observability.recordFallback(primary.getName(), fallback.getName(),
                primaryError == null ? "circuit_open" : "primary_error");

        try {
            ChatResponse r = fallback.chat(request);
            circuitBreaker.recordSuccess(fallback.getName());
            observability.recordProviderSuccess(fallback.getName(), r.latencyMs(), r.inputTokens(), r.outputTokens());
            return r;
        } catch (ProviderException e) {
            circuitBreaker.recordFailure(fallback.getName());
            observability.recordProviderFailure(fallback.getName(), 0, e.getMessage());
            log.error("dispatcher.fallback-failed provider={} status={} msg={}",
                    e.getProviderName(), e.getStatusCode(), e.getMessage());
            throw new AllProvidersFailedException(
                    "All providers failed (primary=" + primary.getName()
                            + ", fallback=" + fallback.getName() + ")",
                    e
            );
        }
    }
}
