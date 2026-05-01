package com.callwise.voiceagent.ai;

import com.callwise.voiceagent.ai.dto.ChatRequest;
import com.callwise.voiceagent.ai.dto.ChatResponse;

/**
 * Abstraction over an LLM provider. {@link com.callwise.voiceagent.ai.AIDispatcher} composes
 * multiple providers in a primary/fallback chain.
 */
public interface ChatProvider {

    /**
     * Synchronous chat completion. Implementations must:
     * <ul>
     *   <li>Map {@link ChatRequest} into the provider's wire format</li>
     *   <li>Translate provider tool/function-call response into {@link ChatResponse#toolCalls()}</li>
     *   <li>Throw {@link com.callwise.voiceagent.exception.ProviderException} on any HTTP / parse error</li>
     * </ul>
     */
    ChatResponse chat(ChatRequest request);

    /** Stable provider identifier ("claude", "groq"). Used for logging, MDC, and metrics. */
    String getName();

    /** Cheap probe of provider health (used by admin endpoints; circuit breaker uses recordSuccess/Failure). */
    boolean isHealthy();
}
