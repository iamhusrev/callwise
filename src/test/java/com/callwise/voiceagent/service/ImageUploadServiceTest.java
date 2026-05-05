package com.callwise.voiceagent.service;

import com.callwise.voiceagent.ai.AIDispatcher;
import com.callwise.voiceagent.ai.dto.VisionResult;
import com.callwise.voiceagent.config.UploadProperties;
import com.callwise.voiceagent.entity.ImageUpload;
import com.callwise.voiceagent.entity.ImageUploadStatus;
import com.callwise.voiceagent.exception.UploadTokenInvalidException;
import com.callwise.voiceagent.repository.ImageUploadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-level tests for {@link ImageUploadService}. The repository is mocked so we can pin
 * exact rows and time-travel for expiry behaviour. The vision dispatcher is mocked too —
 * we want to assert the lifecycle transitions, not that real Anthropic responds.
 */
class ImageUploadServiceTest {

    @TempDir
    Path tempDir;

    private ImageUploadRepository repository;
    private AIDispatcher dispatcher;
    private UploadProperties properties;
    private ImageUploadService service;

    @BeforeEach
    void setUp() {
        repository = mock(ImageUploadRepository.class);
        dispatcher = mock(AIDispatcher.class);
        properties = new UploadProperties();
        properties.setDir(tempDir.toString());
        properties.setMaxBytes(1024 * 1024);
        properties.setTokenTtlMinutes(30);
        properties.setPublicBaseUrl("https://example.invalid");

        // Pass-through save so generated entities keep their fields
        lenient().when(repository.save(any(ImageUpload.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new ImageUploadService(repository, properties, dispatcher, new ObjectMapper());
    }

    @Test
    void createPendingUpload_givenSession_persistsRowWithFreshTokenAndExpiry() {
        ImageUpload row = service.createPendingUpload(42L, "alice@example.com");

        assertThat(row.getCallSessionId()).isEqualTo(42L);
        assertThat(row.getEmail()).isEqualTo("alice@example.com");
        assertThat(row.getStatus()).isEqualTo(ImageUploadStatus.PENDING);
        assertThat(row.getToken()).isNotBlank().hasSizeGreaterThanOrEqualTo(20);
        assertThat(row.getExpiresAt()).isAfter(OffsetDateTime.now().plusMinutes(29));
    }

    @Test
    void createPendingUpload_generatesUniqueTokensAcrossCalls() {
        ImageUpload a = service.createPendingUpload(1L, "a@x.invalid");
        ImageUpload b = service.createPendingUpload(2L, "b@x.invalid");
        assertThat(a.getToken()).isNotEqualTo(b.getToken());
    }

    @Test
    void findValidPending_givenExpiredRow_throwsExpiredAndMarksStatusExpired() {
        ImageUpload row = ImageUpload.builder()
                .id(1L).callSessionId(1L).email("e@x.invalid").token("t1")
                .status(ImageUploadStatus.PENDING)
                .createdAt(OffsetDateTime.now().minusHours(2))
                .updatedAt(OffsetDateTime.now().minusHours(2))
                .expiresAt(OffsetDateTime.now().minusMinutes(5))
                .build();
        when(repository.findByToken("t1")).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.findValidPending("t1"))
                .isInstanceOf(UploadTokenInvalidException.class)
                .extracting("reason")
                .isEqualTo(UploadTokenInvalidException.Reason.EXPIRED);
        assertThat(row.getStatus()).isEqualTo(ImageUploadStatus.EXPIRED);
    }

    @Test
    void findValidPending_givenAlreadyUploaded_throwsAlreadyUsed() {
        ImageUpload row = ImageUpload.builder()
                .id(1L).callSessionId(1L).email("e@x.invalid").token("t2")
                .status(ImageUploadStatus.UPLOADED)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(20))
                .build();
        when(repository.findByToken("t2")).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.findValidPending("t2"))
                .isInstanceOf(UploadTokenInvalidException.class)
                .extracting("reason")
                .isEqualTo(UploadTokenInvalidException.Reason.ALREADY_USED);
    }

    @Test
    void findValidPending_givenUnknownToken_throwsNotFound() {
        when(repository.findByToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findValidPending("nope"))
                .isInstanceOf(UploadTokenInvalidException.class)
                .extracting("reason")
                .isEqualTo(UploadTokenInvalidException.Reason.NOT_FOUND);
    }

    @Test
    void storeImage_givenWrongMime_rejects() {
        ImageUpload row = pendingRow("t3");
        when(repository.findByToken("t3")).thenReturn(Optional.of(row));
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.exe", "application/octet-stream", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.storeImage("t3", file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported content type");
    }

    @Test
    void storeImage_givenOversize_rejects() throws Exception {
        properties.setMaxBytes(10);
        ImageUpload row = pendingRow("t4");
        when(repository.findByToken("t4")).thenReturn(Optional.of(row));
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.jpg", "image/jpeg", new byte[100]);

        assertThatThrownBy(() -> service.storeImage("t4", file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void storeImage_happyPath_writesFileAndFlipsToUploaded() throws Exception {
        ImageUpload row = pendingRow("t5");
        when(repository.findByToken("t5")).thenReturn(Optional.of(row));
        byte[] bytes = "hello".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "p.jpg", "image/jpeg", bytes);

        ImageUpload saved = service.storeImage("t5", file);

        assertThat(saved.getStatus()).isEqualTo(ImageUploadStatus.UPLOADED);
        assertThat(saved.getImageMimeType()).isEqualTo("image/jpeg");
        assertThat(Files.readAllBytes(Path.of(saved.getImagePath()))).isEqualTo(bytes);
    }

    @Test
    void analyzeAsync_visionSuccess_setsAnalyzedAndProvider() throws Exception {
        ImageUpload row = pendingRow("t6");
        row.setStatus(ImageUploadStatus.UPLOADED);
        Path file = tempDir.resolve("t6.jpg");
        Files.write(file, new byte[]{0x10, 0x20});
        row.setImagePath(file.toString());
        row.setImageMimeType("image/jpeg");
        when(repository.findById(99L)).thenReturn(Optional.of(row));
        row.setId(99L);

        VisionResult vr = new VisionResult("washer", List.of("water on floor"),
                "Check the door seal", "Appliance: washer\nVisible issues: water on floor\nSuggested next step: Check the door seal", "claude");
        when(dispatcher.analyzeImage(any(), anyString())).thenReturn(vr);

        service.analyzeAsync(99L);

        assertThat(row.getStatus()).isEqualTo(ImageUploadStatus.ANALYZED);
        assertThat(row.getVisionProvider()).isEqualTo("claude");
        assertThat(row.getVisionResult()).contains("water on floor");
    }

    @Test
    void analyzeAsync_dispatcherThrows_marksFailedWithErrorPayload() throws Exception {
        ImageUpload row = pendingRow("t7");
        row.setStatus(ImageUploadStatus.UPLOADED);
        Path file = tempDir.resolve("t7.jpg");
        Files.write(file, new byte[]{0x01});
        row.setImagePath(file.toString());
        row.setImageMimeType("image/jpeg");
        row.setId(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(row));
        when(dispatcher.analyzeImage(any(), anyString())).thenThrow(new RuntimeException("vision blew up"));

        service.analyzeAsync(7L);

        assertThat(row.getStatus()).isEqualTo(ImageUploadStatus.FAILED);
        assertThat(row.getVisionResult()).contains("vision blew up");
    }

    private ImageUpload pendingRow(String token) {
        return ImageUpload.builder()
                .id(1L).callSessionId(1L).email("e@x.invalid").token(token)
                .status(ImageUploadStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(20))
                .build();
    }
}
