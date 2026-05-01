package com.callwise.voiceagent.ai.tools;

import com.callwise.voiceagent.ai.dto.ToolDefinition;
import com.callwise.voiceagent.service.ConversationService;
import com.callwise.voiceagent.service.SchedulingService;
import com.callwise.voiceagent.service.dto.SlotInfo;
import com.callwise.voiceagent.service.dto.TechnicianAvailability;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Find technicians available for a given ZIP and appliance type.
 *
 * <p>Returns up to 3 candidates, each with their next 3 open slots. The {@code slot_id} in
 * each slot is the handle the AI must pass back to {@link ScheduleAppointmentTool} — the AI
 * is not asked to invent or parse datetimes, which removes a whole class of hallucination.
 */
@Component
public class FindTechniciansTool implements Tool {

    public static final String NAME = "find_technicians";

    private final SchedulingService schedulingService;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    public FindTechniciansTool(
            SchedulingService schedulingService,
            ConversationService conversationService,
            ObjectMapper objectMapper
    ) {
        this.schedulingService = schedulingService;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolDefinition getDefinition() {
        // JSON schema described as a Map so it serialises identically for Anthropic and Groq.
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "zip_code", Map.of(
                                "type", "string",
                                "description", "5-digit US ZIP code"
                        ),
                        "appliance_type", Map.of(
                                "type", "string",
                                "enum", List.of("washer", "dryer", "refrigerator", "dishwasher", "oven", "hvac"),
                                "description", "Appliance category the customer needs help with"
                        )
                ),
                "required", List.of("zip_code", "appliance_type")
        );
        return new ToolDefinition(
                NAME,
                "Find available technicians by ZIP code and appliance type. Returns up to 3 technicians, each with their next 3 open slots and a slot_id you must pass to schedule_appointment. Use after the customer agrees to schedule a visit.",
                schema
        );
    }

    @Override
    public String execute(Map<String, Object> input) {
        String zip = (String) input.get("zip_code");
        String appliance = (String) input.get("appliance_type");
        if (zip == null || appliance == null) {
            return "{\"error\":\"missing_required_field\"}";
        }

        // Back-fill the structured appliance_type on the parent session so the admin
        // list view surfaces the category for in-progress calls (idempotent).
        conversationService.recordApplianceType(ToolContext.getSessionId(), appliance);

        List<TechnicianAvailability> results = schedulingService.findAvailableTechnicians(zip, appliance);
        List<Map<String, Object>> technicians = new ArrayList<>();
        for (TechnicianAvailability ta : results) {
            List<Map<String, Object>> slots = new ArrayList<>();
            for (SlotInfo s : ta.nextSlots()) {
                slots.add(Map.of(
                        "slot_id", s.slotId(),
                        "display", s.displayLabel()
                ));
            }
            technicians.add(Map.of(
                    "technician_id", ta.technicianId(),
                    "name", ta.name(),
                    "slots", slots
            ));
        }

        try {
            return objectMapper.writeValueAsString(Map.of(
                    "zip_code", zip,
                    "appliance_type", appliance,
                    "technicians", technicians
            ));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }
}
