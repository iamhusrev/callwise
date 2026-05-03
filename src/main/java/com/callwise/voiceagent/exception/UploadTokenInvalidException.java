package com.callwise.voiceagent.exception;

/**
 * Tier 3 — thrown when a token in the upload URL doesn't match a row, has expired, or
 * the row is in a state that no longer accepts uploads (already UPLOADED/ANALYZED/FAILED).
 *
 * <p>The {@code reason} field lets the controller pick the right HTTP status:
 * {@code NOT_FOUND}, {@code GONE}, etc.
 */
public class UploadTokenInvalidException extends RuntimeException {

    public enum Reason { NOT_FOUND, EXPIRED, ALREADY_USED }

    private final Reason reason;

    public UploadTokenInvalidException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
