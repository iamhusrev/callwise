package com.callwise.voiceagent.ai.tools;

import com.callwise.voiceagent.entity.ImageUpload;
import com.callwise.voiceagent.entity.ImageUploadStatus;
import com.callwise.voiceagent.service.ImageUploadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CheckImageStatusToolTest {

    private ImageUploadService uploadService;
    private CheckImageStatusTool tool;

    @BeforeEach
    void setUp() {
        uploadService = mock(ImageUploadService.class);
        tool = new CheckImageStatusTool(uploadService, new ObjectMapper());
        ToolContext.set("CA1", 11L);
    }

    @AfterEach
    void tearDown() {
        ToolContext.clear();
    }

    @Test
    void execute_noUploadForSession_returnsStatusNone() {
        when(uploadService.getLatestForSession(11L)).thenReturn(Optional.empty());
        String r = tool.execute(Map.of());
        assertThat(r).contains("\"status\":\"none\"");
    }

    @Test
    void execute_pendingRow_returnsPendingWithoutVision() {
        when(uploadService.getLatestForSession(11L)).thenReturn(Optional.of(row(ImageUploadStatus.PENDING, null, null)));
        String r = tool.execute(Map.of());
        assertThat(r).contains("\"status\":\"pending\"").doesNotContain("vision");
    }

    @Test
    void execute_analyzedRow_includesParsedVisionObject() {
        String visionJson = "{\"appliance_type\":\"washer\",\"visible_issues\":[\"leak\"]}";
        when(uploadService.getLatestForSession(11L)).thenReturn(Optional.of(row(ImageUploadStatus.ANALYZED, visionJson, "claude")));
        String r = tool.execute(Map.of());
        assertThat(r).contains("\"status\":\"analyzed\"")
                .contains("\"vision_provider\":\"claude\"")
                .contains("\"appliance_type\":\"washer\"")
                .contains("\"leak\"");
    }

    @Test
    void execute_noContext_returnsNone() {
        ToolContext.clear();
        String r = tool.execute(Map.of());
        assertThat(r).contains("\"status\":\"none\"");
    }

    private static ImageUpload row(ImageUploadStatus status, String visionResult, String provider) {
        return ImageUpload.builder()
                .id(1L).callSessionId(11L).email("e@x.invalid").token("tok")
                .status(status)
                .visionResult(visionResult)
                .visionProvider(provider)
                .createdAt(OffsetDateTime.now().minusSeconds(10))
                .updatedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(30))
                .build();
    }
}
