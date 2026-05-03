package com.callwise.voiceagent.ai.tools;

import com.callwise.voiceagent.ai.dto.ToolDefinition;
import com.callwise.voiceagent.entity.ImageUpload;
import com.callwise.voiceagent.service.ImageUploadService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tier 3 — AI-callable tool: "did the customer upload the photo yet, and what did vision see?".
 *
 * <p>Pulls the most recent {@code image_upload} row for the active call session via
 * {@link ToolContext#getSessionId()}. Result envelope is intentionally compact so the AI
 * can re-narrate it to the caller without burning tokens.
 */
@Component
public class CheckImageStatusTool implements Tool {

    public static final String NAME = "check_image_status";

    private final ImageUploadService uploadService;
    private final ObjectMapper objectMapper;

    public CheckImageStatusTool(ImageUploadService uploadService, ObjectMapper objectMapper) {
        this.uploadService = uploadService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolDefinition getDefinition() {
        // No inputs — this tool is scoped to the current call automatically.
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false
        );
        return new ToolDefinition(
                NAME,
                "Check the latest photo upload status for THIS call. Returns one of: none, pending, uploaded, analyzed, failed, expired. When ANALYZED, the result includes appliance_type, visible_issues, and suggested_next_step from the vision model. Call this on later turns AFTER you have already requested an upload, to see if the photo arrived.",
                schema
        );
    }

    @Override
    public String execute(Map<String, Object> input) {
        Long sessionId = ToolContext.getSessionId();
        if (sessionId == null) {
            return write(Map.of("status", "none"));
        }
        Optional<ImageUpload> opt = uploadService.getLatestForSession(sessionId);
        if (opt.isEmpty()) {
            return write(Map.of("status", "none"));
        }
        ImageUpload row = opt.get();
        long ageSeconds = Math.max(0, OffsetDateTime.now().toEpochSecond() - row.getCreatedAt().toEpochSecond());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", row.getStatus().name().toLowerCase(Locale.ROOT));
        envelope.put("age_seconds", ageSeconds);

        if (row.getVisionResult() != null && !row.getVisionResult().isBlank()) {
            // vision_result is JSONB persisted as a JSON string by ImageUploadService.toJson;
            // re-emit it parsed so the AI sees a structured object, not a string.
            try {
                JsonNode parsed = objectMapper.readTree(row.getVisionResult());
                envelope.put("vision", parsed);
            } catch (Exception ignored) {
                envelope.put("vision_raw", row.getVisionResult());
            }
        }
        if (row.getVisionProvider() != null) {
            envelope.put("vision_provider", row.getVisionProvider());
        }
        return write(envelope);
    }

    private String write(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"status\":\"error\"}";
        }
    }
}
