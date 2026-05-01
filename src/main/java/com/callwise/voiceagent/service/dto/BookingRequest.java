package com.callwise.voiceagent.service.dto;

/**
 * Input to {@link com.callwise.voiceagent.service.SchedulingService#bookAppointment}.
 */
public record BookingRequest(
        Long callSessionId,
        Long technicianId,
        Long slotId,
        String customerName,
        String customerPhone,
        String customerAddress,
        String applianceType,
        String problemSummary
) {
}
