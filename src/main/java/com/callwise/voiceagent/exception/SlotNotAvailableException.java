package com.callwise.voiceagent.exception;

/**
 * Raised when a booking attempt loses the race for a slot — caught by the tool layer and
 * surfaced to the AI as a structured error so it can offer the next available slot.
 */
public class SlotNotAvailableException extends RuntimeException {

    private final Long slotId;

    public SlotNotAvailableException(Long slotId, String message) {
        super(message);
        this.slotId = slotId;
    }

    public Long getSlotId() {
        return slotId;
    }
}
