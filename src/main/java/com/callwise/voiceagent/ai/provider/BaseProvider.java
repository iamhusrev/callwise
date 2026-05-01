package com.callwise.voiceagent.ai.provider;

import com.callwise.voiceagent.ai.ChatProvider;

/**
 * Skeleton with shared helpers for concrete providers. Kept thin — provider-specific
 * mapping (request body shape, response parsing) belongs in the subclass, not here.
 */
public abstract class BaseProvider implements ChatProvider {

    /** Wall-clock millis for latency tracking, using {@link System#nanoTime()} for monotonicity. */
    protected static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
