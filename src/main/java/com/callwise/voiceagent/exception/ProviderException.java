package com.callwise.voiceagent.exception;

/**
 * Thrown when a {@link com.callwise.voiceagent.ai.ChatProvider} fails. Carries the provider
 * name so the dispatcher can log the failing provider and decide on fallback.
 */
public class ProviderException extends RuntimeException {

    private final String providerName;
    private final int statusCode;

    public ProviderException(String providerName, String message) {
        this(providerName, message, -1, null);
    }

    public ProviderException(String providerName, String message, int statusCode) {
        this(providerName, message, statusCode, null);
    }

    public ProviderException(String providerName, String message, int statusCode, Throwable cause) {
        super("[" + providerName + "] " + message, cause);
        this.providerName = providerName;
        this.statusCode = statusCode;
    }

    public String getProviderName() {
        return providerName;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
