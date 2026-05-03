package com.callwise.voiceagent.ai.tools;

import com.callwise.voiceagent.ai.dto.ToolDefinition;
import com.callwise.voiceagent.config.UploadProperties;
import com.callwise.voiceagent.entity.ImageUpload;
import com.callwise.voiceagent.service.EmailService;
import com.callwise.voiceagent.service.ImageUploadService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tier 3 — AI-callable tool: "I think a photo would help — please send the customer an upload link".
 *
 * <p>Creates a pending {@code image_upload} row, then dispatches an HTML email through
 * {@link EmailService}. The AI gets back a status JSON it can read back to the caller
 * verbatim, no datetime parsing or token handling required from the model.
 */
@Component
public class RequestImageUploadTool implements Tool {

    public static final String NAME = "request_image_upload";

    private static final Logger log = LoggerFactory.getLogger(RequestImageUploadTool.class);
    // RFC5322-lite: good enough to weed out obvious typos before we waste an SMTP round-trip.
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final ImageUploadService uploadService;
    private final EmailService emailService;
    private final UploadProperties properties;
    private final ObjectMapper objectMapper;
    private final int appPort;

    public RequestImageUploadTool(
            ImageUploadService uploadService,
            EmailService emailService,
            UploadProperties properties,
            ObjectMapper objectMapper,
            @Value("${server.port:8080}") int appPort
    ) {
        this.uploadService = uploadService;
        this.emailService = emailService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.appPort = appPort;
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
                        "email", Map.of(
                                "type", "string",
                                "description", "Caller's email address — read back to confirm before calling."
                        ),
                        "reason", Map.of(
                                "type", "string",
                                "description", "Short human-readable reason explaining why a photo would help (e.g. 'to see the leak under your fridge')."
                        )
                ),
                "required", List.of("email", "reason")
        );
        return new ToolDefinition(
                NAME,
                "Send the caller a one-time link by email so they can upload a photo of the appliance. Use ONLY when a visual would meaningfully help the diagnosis and the caller has agreed. Always read the email address back to confirm before calling. The link expires in 30 minutes.",
                schema
        );
    }

    @Override
    public String execute(Map<String, Object> input) {
        String email = strOrNull(input.get("email"));
        String reason = strOrNull(input.get("reason"));

        if (email == null || !EMAIL.matcher(email).matches()) {
            return jsonOrFallback(Map.of("status", "error", "error", "invalid_email"));
        }
        Long sessionId = ToolContext.getSessionId();
        if (sessionId == null) {
            return jsonOrFallback(Map.of("status", "error", "error", "no_call_context"));
        }

        ImageUpload row = uploadService.createPendingUpload(sessionId, email);
        String base = properties.getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            // Defensive default: a relative URL would resolve against MailHog's hostname when
            // the recipient clicks from the MailHog inbox, producing a 404 on :8025. Fall back
            // to an absolute localhost URL so dev/demo testing without ngrok still works.
            base = "http://localhost:" + appPort;
            log.warn("request_image_upload: PUBLIC_BASE_URL not set — defaulting link base to {}. "
                    + "Set PUBLIC_BASE_URL in .env (use the ngrok URL for real Twilio calls).", base);
        }
        String link = stripTrailingSlash(base) + "/uploads/" + row.getToken();

        try {
            emailService.sendUploadLink(email, link, reason, properties.getTokenTtlMinutes());
        } catch (Exception e) {
            log.error("request_image_upload: send failed err={}", e.getMessage());
            return jsonOrFallback(Map.of(
                    "status", "error",
                    "error", "email_send_failed",
                    "message", String.valueOf(e.getMessage())
            ));
        }

        return jsonOrFallback(Map.of(
                "status", "sent",
                "email", email,
                "expires_in_minutes", properties.getTokenTtlMinutes()
        ));
    }

    private String jsonOrFallback(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"status\":\"error\",\"error\":\"serialization_failed\"}";
        }
    }

    private static String strOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
