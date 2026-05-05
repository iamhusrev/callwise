package com.callwise.voiceagent.service;

import com.callwise.voiceagent.ai.AIDispatcher;
import com.callwise.voiceagent.ai.dto.VisionResult;
import com.callwise.voiceagent.config.UploadProperties;
import com.callwise.voiceagent.entity.ImageUpload;
import com.callwise.voiceagent.entity.ImageUploadStatus;
import com.callwise.voiceagent.exception.UploadTokenInvalidException;
import com.callwise.voiceagent.repository.ImageUploadRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tier 3 — orchestrates the lifecycle of an image upload: pending → uploaded → analyzed.
 *
 * <p>Token generation uses 24 random bytes via {@link SecureRandom}, base64-url-encoded to
 * a 32-character string (192 bits of entropy — well past brute-force range). Vision
 * analysis is fired asynchronously after the bytes hit disk so the HTTP POST responds
 * immediately to the caller's browser.
 */
@Service
public class ImageUploadService {

    private static final Logger log = LoggerFactory.getLogger(ImageUploadService.class);

    /** Allow-list — any other MIME and the upload is rejected. */
    private static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png", "image/webp");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ImageUploadRepository repository;
    private final UploadProperties properties;
    private final AIDispatcher aiDispatcher;
    private final ObjectMapper objectMapper;

    public ImageUploadService(
            ImageUploadRepository repository,
            UploadProperties properties,
            // Lazy: AIDispatcher itself doesn't depend on us, but keeping the edge lazy
            // avoids any future cycle if a tool ends up depending on this service.
            @Lazy AIDispatcher aiDispatcher,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.properties = properties;
        this.aiDispatcher = aiDispatcher;
        this.objectMapper = objectMapper;
    }

    /** Create a PENDING row + return the persistable token. Email is sent by the caller. */
    public ImageUpload createPendingUpload(Long callSessionId, String email) {
        if (callSessionId == null) {
            throw new IllegalArgumentException("callSessionId required");
        }
        String token = generateToken();
        OffsetDateTime now = OffsetDateTime.now();
        ImageUpload row = ImageUpload.builder()
                .callSessionId(callSessionId)
                .email(email)
                .token(token)
                .status(ImageUploadStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plusMinutes(properties.getTokenTtlMinutes()))
                .build();
        return repository.save(row);
    }

    /** Look up a token, accepting only PENDING rows that haven't expired. Used by GET. */
    public ImageUpload findValidPending(String token) {
        ImageUpload row = repository.findByToken(token)
                .orElseThrow(() -> new UploadTokenInvalidException(
                        UploadTokenInvalidException.Reason.NOT_FOUND, "no such token"));
        if (row.getExpiresAt().isBefore(OffsetDateTime.now())) {
            // Best-effort persist of EXPIRED so admin queries see the lifecycle.
            if (row.getStatus() == ImageUploadStatus.PENDING) {
                row.setStatus(ImageUploadStatus.EXPIRED);
                repository.save(row);
            }
            throw new UploadTokenInvalidException(
                    UploadTokenInvalidException.Reason.EXPIRED, "token expired");
        }
        if (row.getStatus() != ImageUploadStatus.PENDING) {
            throw new UploadTokenInvalidException(
                    UploadTokenInvalidException.Reason.ALREADY_USED, "token already used");
        }
        return row;
    }

    /**
     * Validate + persist the uploaded bytes, then fire vision analysis asynchronously.
     */
    public ImageUpload storeImage(String token, MultipartFile file) throws IOException {
        ImageUpload row = findValidPending(token);

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("empty upload");
        }
        if (file.getSize() > properties.getMaxBytes()) {
            throw new IllegalArgumentException("file too large: " + file.getSize() + " bytes");
        }

        String mime = file.getContentType();
        if (mime == null || !ALLOWED_MIME.contains(mime)) {
            throw new IllegalArgumentException("unsupported content type: " + mime);
        }

        Path dir = Paths.get(properties.getDir());
        Files.createDirectories(dir);
        String ext = switch (mime) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
        Path target = dir.resolve(token + ext);
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        row.setImagePath(target.toString());
        row.setImageMimeType(mime);
        row.setStatus(ImageUploadStatus.UPLOADED);
        ImageUpload saved = repository.save(row);

        log.info("upload.stored token={} sessionId={} bytes={} mime={}",
                token, row.getCallSessionId(), file.getSize(), mime);

        // Fire-and-forget vision analysis; the AI's check_image_status tool will poll.
        analyzeAsync(saved.getId());
        return saved;
    }

    /** Read-only status lookup (used by check_image_status tool). */
    public Optional<ImageUpload> getLatestForSession(Long callSessionId) {
        if (callSessionId == null) return Optional.empty();
        return repository.findFirstByCallSessionIdOrderByCreatedAtDescIdDesc(callSessionId);
    }

    @Async("visionExecutor")
    public void analyzeAsync(Long uploadId) {
        ImageUpload row = repository.findById(uploadId).orElse(null);
        if (row == null) {
            log.warn("vision.skip uploadId={} — row vanished", uploadId);
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(row.getImagePath()));
            VisionResult result = aiDispatcher.analyzeImage(bytes, row.getImageMimeType());
            row.setVisionResult(toJson(result));
            row.setVisionProvider(result.providerName());
            row.setStatus(ImageUploadStatus.ANALYZED);
            repository.save(row);
            log.info("vision.ok uploadId={} provider={} appliance={} issues={}",
                    uploadId, result.providerName(), result.applianceType(), result.visibleIssues().size());
        } catch (Exception e) {
            row.setStatus(ImageUploadStatus.FAILED);
            row.setVisionResult(toJson(Map.of("error", String.valueOf(e.getMessage()))));
            repository.save(row);
            log.error("vision.failed uploadId={} err={}", uploadId, e.getMessage());
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String toJson(VisionResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("appliance_type", result.applianceType());
        map.put("visible_issues", result.visibleIssues());
        map.put("suggested_next_step", result.suggestedNextStep());
        map.put("raw_text", result.rawText());
        map.put("provider", result.providerName());
        return toJson((Object) map);
    }

    private static String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
