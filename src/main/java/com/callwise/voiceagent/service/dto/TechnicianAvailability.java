package com.callwise.voiceagent.service.dto;

import java.util.List;

/**
 * A candidate technician + their next few open slots, ready to be quoted to the customer
 * by the AI. Returned by {@link com.callwise.voiceagent.service.SchedulingService#findAvailableTechnicians}.
 */
public record TechnicianAvailability(
        Long technicianId,
        String name,
        List<SlotInfo> nextSlots
) {
}
