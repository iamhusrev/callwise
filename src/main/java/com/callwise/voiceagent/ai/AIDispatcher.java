package com.callwise.voiceagent.ai;

import com.callwise.voiceagent.ai.circuit.CircuitBreaker;
import com.callwise.voiceagent.ai.dto.ChatRequest;
import com.callwise.voiceagent.ai.dto.ChatResponse;
import com.callwise.voiceagent.ai.dto.ImageContent;
import com.callwise.voiceagent.ai.dto.Message;
import com.callwise.voiceagent.ai.dto.VisionResult;
import com.callwise.voiceagent.ai.provider.ClaudeProvider;
import com.callwise.voiceagent.ai.provider.GroqProvider;
import com.callwise.voiceagent.exception.AllProvidersFailedException;
import com.callwise.voiceagent.exception.ProviderException;
import com.callwise.voiceagent.service.ObservabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

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
    private final int visionMaxTokens;
    private volatile String visionSystemPrompt;

    public AIDispatcher(
            ClaudeProvider primary,
            GroqProvider fallback,
            CircuitBreaker circuitBreaker,
            ObservabilityService observability,
            @Value("${callwise.vision.max-tokens:512}") int visionMaxTokens
    ) {
        this.primary = primary;
        this.fallback = fallback;
        this.circuitBreaker = circuitBreaker;
        this.observability = observability;
        this.visionMaxTokens = visionMaxTokens;
        this.visionSystemPrompt = loadVisionPrompt();
    }

    private static String loadVisionPrompt() {
        try {
            byte[] bytes = new ClassPathResource("prompts/vision-prompt.txt").getInputStream().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Fall back to a minimal inline prompt if the file is missing — keeps the system
            // healthy in the unlikely event of a packaging mishap.
            return "Identify the appliance in the photo and any visible issue. Respond on three lines: 'Appliance: ...', 'Visible issues: ...', 'Suggested next step: ...'.";
        }
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

    /**
     * Tier 3 — analyse an uploaded image and return the parsed structured result.
     *
     * <p>Reuses {@link #diagnose} (and therefore the circuit breaker + fallback chain) by
     * building a one-shot {@link ChatRequest} with no history, no tools, the dedicated vision
     * system prompt, and the image attached to the single user turn.
     *
     * @param imageBytes raw bytes of the uploaded photo
     * @param mediaType  MIME type as detected from the upload (e.g. {@code image/jpeg})
     * @return structured vision result, parsed from the LLM's three-line response
     * @throws AllProvidersFailedException if both providers fail or are circuit-open
     */
    public VisionResult analyzeImage(byte[] imageBytes, String mediaType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes must be non-empty");
        }
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        ImageContent image = new ImageContent(mediaType, base64);

        ChatRequest req = new ChatRequest(
                List.of(Message.user("Please analyse this appliance photo using the format I gave you.")),
                visionSystemPrompt,
                List.of(),       // no tools — we want a direct answer
                0.0,             // deterministic
                visionMaxTokens,
                List.of(image)
        );

        ChatResponse r = diagnose(req);
        return parseVisionResponse(r);
    }

    static VisionResult parseVisionResponse(ChatResponse r) {
        String raw = r == null || r.text() == null ? "" : r.text().trim();
        String appliance = "unknown";
        java.util.ArrayList<String> issues = new java.util.ArrayList<>();
        String suggestion = null;

        for (String rawLine : raw.split("\\r?\\n")) {
            String line = rawLine.trim();
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("appliance:")) {
                appliance = line.substring("appliance:".length()).trim().toLowerCase(java.util.Locale.ROOT);
            } else if (lower.startsWith("visible issues:")) {
                String rest = line.substring("visible issues:".length()).trim();
                if (!rest.isEmpty() && !rest.equalsIgnoreCase("none visible") && !rest.equalsIgnoreCase("none")) {
                    issues.addAll(Arrays.stream(rest.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList());
                }
            } else if (lower.startsWith("suggested next step:")) {
                suggestion = line.substring("suggested next step:".length()).trim();
            }
        }
        return new VisionResult(
                appliance.isEmpty() ? "unknown" : appliance,
                issues,
                suggestion,
                raw,
                r == null ? null : r.providerName()
        );
    }
}
