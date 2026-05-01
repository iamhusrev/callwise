package com.callwise.voiceagent.service.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One bookable window. {@code displayLabel} is the human-readable form the AI quotes
 * back to the customer (e.g. "Monday May 5 at 2:00 PM").
 */
public record SlotInfo(
        Long slotId,
        LocalDate slotDate,
        LocalTime startTime,
        LocalTime endTime,
        String displayLabel
) {
}
