package com.callwise.voiceagent.controller;

import com.callwise.voiceagent.config.UploadProperties;
import com.callwise.voiceagent.entity.CallSession;
import com.callwise.voiceagent.entity.ImageUpload;
import com.callwise.voiceagent.repository.CallSessionRepository;
import com.callwise.voiceagent.service.EmailService;
import com.callwise.voiceagent.service.ImageUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dev-only endpoints for poking the system from Postman / curl without going through
 * Twilio + the AI tool flow. Lives outside {@link AdminController} so the read-only
 * contract there stays intact.
 *
 * <p><strong>Not for production.</strong> No auth (matches existing admin endpoints —
 * take-home scope), and trivially mutates DB state. In production this would either be
 * removed or gated by a Spring profile + auth.
 */
@RestController
@RequestMapping(value = "/dev", produces = MediaType.APPLICATION_JSON_VALUE)
public class DevController {

    private static final Logger log = LoggerFactory.getLogger(DevController.class);

    private final ImageUploadService uploadService;
    private final EmailService emailService;
    private final UploadProperties properties;
    private final CallSessionRepository sessionRepository;
    private final int appPort;

    public DevController(
            ImageUploadService uploadService,
            EmailService emailService,
            UploadProperties properties,
            CallSessionRepository sessionRepository,
            @Value("${server.port:8080}") int appPort
    ) {
        this.uploadService = uploadService;
        this.emailService = emailService;
        this.properties = properties;
        this.sessionRepository = sessionRepository;
        this.appPort = appPort;
    }

    /**
     * Triggers the full Tier 3 email-link flow without needing Twilio + the AI loop.
     * Returns the generated token + link so you can drop the link straight into a browser
     * if you don't want to click through MailHog.
     *
     * <p>Body: {@code { "email": "demo@example.com", "reason": "...", "callSid": "..." }}.
     * All fields optional — defaults provided so a bare {@code {}} works too.
     */
    @PostMapping(value = "/uploads/test-email", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> sendTestEmail(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> in = body == null ? Map.of() : body;
        String email = in.getOrDefault("email", "demo@example.com");
        String reason = in.getOrDefault("reason", "test mail from /dev/uploads/test-email");
        String callSid = in.getOrDefault("callSid", "CA-DEBUG-" + UUID.randomUUID());

        CallSession session = sessionRepository.findByCallSid(callSid)
                .orElseGet(() -> sessionRepository.save(CallSession.builder()
                        .callSid(callSid)
                        .phoneNumber("+15555550000")
                        .startedAt(OffsetDateTime.now())
                        .status("ACTIVE")
                        .build()));

        ImageUpload row = uploadService.createPendingUpload(session.getId(), email);

        String base = properties.getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            base = "http://localhost:" + appPort;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String link = base + "/uploads/" + row.getToken();

        emailService.sendUploadLink(email, link, reason, properties.getTokenTtlMinutes());

        log.info("dev.test-email.sent email={} callSid={} token={} link={}",
                email, callSid, row.getToken(), link);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "sent");
        resp.put("email", email);
        resp.put("callSid", callSid);
        resp.put("token", row.getToken());
        resp.put("link", link);
        resp.put("expires_in_minutes", properties.getTokenTtlMinutes());
        return ResponseEntity.ok(resp);
    }
}
