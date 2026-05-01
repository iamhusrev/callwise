package com.callwise.voiceagent.service;

import com.callwise.voiceagent.entity.CallMetrics;
import com.callwise.voiceagent.repository.CallMetricsRepository;
import com.callwise.voiceagent.repository.CallSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Records every AI turn to {@code call_metrics} (cost-aware, async) and exposes aggregate reads
 * to the admin layer.
 *
 * <p>The dispatcher calls these methods synchronously from the voice path, so the public methods
 * stay non-blocking and delegate to {@link #persistAsync} which runs on the {@code metricsExecutor}
 * pool — a 50 ms DB write should never live in the Twilio 10 s budget.
 *
 * <p>Pricing tables are kept inline as constants. CLAUDE.md note: change them in one place when
 * a provider's rate card moves.
 */
@Service
public class ObservabilityService {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityService.class);

    /** USD per million tokens (input, output). Update when a provider changes their rate card. */
    private static final Map<String, BigDecimal[]> PRICING_PER_MTOK = Map.of(
            "claude", new BigDecimal[]{ new BigDecimal("1.00"), new BigDecimal("5.00") },
            "groq",   new BigDecimal[]{ new BigDecimal("0.59"), new BigDecimal("0.79") }
    );

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    /*
     * Tiny snapshot ThreadLocals. The orchestrator calls bindTurn() right before invoking the
     * dispatcher; persistAsync() reads them. We can't rely on MDC inside the @Async method
     * because Spring's default executor doesn't propagate MDC across thread boundaries — so we
     * snapshot the values explicitly on the caller side.
     */
    private static final ThreadLocal<String> currentCallSid = new ThreadLocal<>();
    private static final ThreadLocal<Integer> currentTurn = new ThreadLocal<>();

    private final CallMetricsRepository metricsRepository;
    private final CallSessionRepository sessionRepository;

    public ObservabilityService(
            CallMetricsRepository metricsRepository,
            CallSessionRepository sessionRepository
    ) {
        this.metricsRepository = metricsRepository;
        this.sessionRepository = sessionRepository;
    }

    public void recordProviderSuccess(String provider, long latencyMs, int inputTokens, int outputTokens) {
        log.debug("metrics.success provider={} latencyMs={} in={} out={}",
                provider, latencyMs, inputTokens, outputTokens);
        BigDecimal cost = computeCost(provider, inputTokens, outputTokens);
        persistAsync(currentCallSid.get(), currentTurn.get(),
                provider, (int) latencyMs, inputTokens, outputTokens, cost, true, null);
    }

    public void recordProviderFailure(String provider, long latencyMs, String error) {
        log.warn("metrics.failure provider={} latencyMs={} error={}", provider, latencyMs, error);
        persistAsync(currentCallSid.get(), currentTurn.get(),
                provider, (int) latencyMs, 0, 0, BigDecimal.ZERO, false, error);
    }

    public void recordFallback(String fromProvider, String toProvider, String reason) {
        log.warn("metrics.fallback from={} to={} reason={}", fromProvider, toProvider, reason);
        // Fallback events are observability-only — the actual fallback turn writes its own
        // success/failure row, so we don't double-count by persisting here.
    }

    /* ===== Read API for AdminController ===== */

    public List<CallMetrics> findForSession(Long sessionId) {
        return metricsRepository.findByCallSessionIdOrderByCreatedAtAsc(sessionId);
    }

    public List<CallMetricsRepository.ProviderTotal> aggregateByProvider() {
        return metricsRepository.aggregateByProvider();
    }

    public BigDecimal totalCostUsd() {
        BigDecimal v = metricsRepository.totalCostUsd();
        return v == null ? BigDecimal.ZERO : v;
    }

    public long totalTokens() {
        return metricsRepository.totalTokens();
    }

    /** Called by the orchestrator before each AI turn. Cleared by {@link #clearTurn()} in finally. */
    public void bindTurn(String callSid, int turnNumber) {
        currentCallSid.set(callSid);
        currentTurn.set(turnNumber);
        if (callSid != null) MDC.put("callSid", callSid);
        MDC.put("turnNumber", String.valueOf(turnNumber));
    }

    public void clearTurn() {
        currentCallSid.remove();
        currentTurn.remove();
        MDC.remove("callSid");
        MDC.remove("turnNumber");
    }

    /* ===== internals ===== */

    private BigDecimal computeCost(String provider, int inputTokens, int outputTokens) {
        BigDecimal[] pricing = PRICING_PER_MTOK.get(provider == null ? "" : provider.toLowerCase());
        if (pricing == null) return BigDecimal.ZERO;
        BigDecimal in = pricing[0].multiply(BigDecimal.valueOf(inputTokens))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal out = pricing[1].multiply(BigDecimal.valueOf(outputTokens))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        return in.add(out).setScale(6, RoundingMode.HALF_UP);
    }

    @Async("metricsExecutor")
    void persistAsync(
            String callSid, Integer turnNumber,
            String provider, int latencyMs, int inputTokens, int outputTokens,
            BigDecimal cost, boolean success, String errorMessage
    ) {
        try {
            if (callSid == null) {
                log.debug("metrics.skip-persist reason=no-callsid provider={}", provider);
                return;
            }
            sessionRepository.findByCallSid(callSid).ifPresent(s -> {
                CallMetrics row = CallMetrics.builder()
                        .callSessionId(s.getId())
                        .turnNumber(turnNumber)
                        .provider(provider)
                        .latencyMs(latencyMs)
                        .inputTokens(inputTokens)
                        .outputTokens(outputTokens)
                        .costUsd(cost)
                        .success(success)
                        .errorMessage(errorMessage)
                        .build();
                metricsRepository.save(row);
            });
        } catch (Exception e) {
            // Never let metrics failure surface — would regress @Async fire-and-forget contract.
            log.warn("metrics.persist-failed provider={} error={}", provider, e.getMessage());
        }
    }
}
