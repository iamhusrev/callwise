package com.callwise.voiceagent.ai.tools;

import com.callwise.voiceagent.ai.dto.ToolDefinition;
import com.callwise.voiceagent.entity.Appointment;
import com.callwise.voiceagent.exception.SlotNotAvailableException;
import com.callwise.voiceagent.service.SchedulingService;
import com.callwise.voiceagent.service.dto.BookingRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Book an appointment in a slot the AI received from {@link FindTechniciansTool}.
 *
 * <p>Pulls {@code callSessionId} from {@link ToolContext} so it isn't part of the AI-facing
 * schema (the model has no concept of session ids and must not be asked to invent one). Returns
 * the {@link SlotNotAvailableException} as a structured error envelope so the AI can recover —
 * typically by re-calling find_technicians and offering the next slot.
 */
@Component
public class ScheduleAppointmentTool implements Tool {

    public static final String NAME = "schedule_appointment";

    private final SchedulingService schedulingService;
    private final ObjectMapper objectMapper;

    public ScheduleAppointmentTool(SchedulingService schedulingService, ObjectMapper objectMapper) {
        this.schedulingService = schedulingService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "technician_id", Map.of(
                                "type", "integer",
                                "description", "ID returned from find_technicians"
                        ),
                        "slot_id", Map.of(
                                "type", "integer",
                                "description", "slot_id returned from find_technicians"
                        ),
                        "customer_name", Map.of(
                                "type", "string",
                                "description", "Full name of the customer"
                        ),
                        "customer_phone", Map.of(
                                "type", "string",
                                "description", "Best contact phone in E.164 form (e.g. +14155552671). Use the inbound caller's number if the customer doesn't volunteer another."
                        ),
                        "customer_address", Map.of(
                                "type", "string",
                                "description", "Service address (street + city + state + ZIP)"
                        ),
                        "appliance_type", Map.of(
                                "type", "string",
                                "description", "Same enum as find_technicians (washer/dryer/refrigerator/dishwasher/oven/hvac)"
                        ),
                        "problem_summary", Map.of(
                                "type", "string",
                                "description", "1-2 sentence diagnostic summary the technician will see"
                        )
                ),
                "required", List.of(
                        "technician_id", "slot_id", "customer_name",
                        "customer_address", "appliance_type", "problem_summary"
                )
        );
        return new ToolDefinition(
                NAME,
                "Book an appointment with a specific technician at a slot returned by find_technicians. Only call after the customer has verbally confirmed the technician and slot.",
                schema
        );
    }

    @Override
    public String execute(Map<String, Object> input) {
        Long sessionId = ToolContext.getSessionId();
        if (sessionId == null) {
            return "{\"error\":\"no_session\",\"message\":\"call session is not bound to this turn\"}";
        }

        BookingRequest req;
        try {
            req = new BookingRequest(
                    sessionId,
                    toLong(input.get("technician_id")),
                    toLong(input.get("slot_id")),
                    (String) input.get("customer_name"),
                    (String) input.get("customer_phone"),
                    (String) input.get("customer_address"),
                    (String) input.get("appliance_type"),
                    (String) input.get("problem_summary")
            );
        } catch (Exception parseError) {
            return "{\"error\":\"invalid_arguments\",\"message\":\"" + safe(parseError.getMessage()) + "\"}";
        }

        try {
            Appointment booked = schedulingService.bookAppointment(req);
            return objectMapper.writeValueAsString(Map.of(
                    "appointment_id", booked.getId(),
                    "status", booked.getStatus(),
                    "technician_id", booked.getTechnicianId(),
                    "slot_id", booked.getAvailabilitySlotId()
            ));
        } catch (SlotNotAvailableException e) {
            return errorJson("slot_not_available", e.getMessage(), e.getSlotId());
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    private String errorJson(String code, String message, Long slotId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "error", code,
                    "slot_id", slotId == null ? -1L : slotId,
                    "message", message == null ? "" : message
            ));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"" + code + "\"}";
        }
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\"", "'").replace("\n", " ");
    }
}
