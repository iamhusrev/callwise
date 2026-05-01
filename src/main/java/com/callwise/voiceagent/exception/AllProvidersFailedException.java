package com.callwise.voiceagent.exception;

/**
 * Raised by {@link com.callwise.voiceagent.ai.AIDispatcher} when every provider in the chain
 * has either tripped its circuit breaker or returned a {@link ProviderException}.
 */
public class AllProvidersFailedException extends RuntimeException {

    public AllProvidersFailedException(String message) {
        super(message);
    }

    public AllProvidersFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
