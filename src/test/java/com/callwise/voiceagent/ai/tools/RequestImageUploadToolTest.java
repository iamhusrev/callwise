package com.callwise.voiceagent.ai.tools;

import com.callwise.voiceagent.config.UploadProperties;
import com.callwise.voiceagent.entity.ImageUpload;
import com.callwise.voiceagent.entity.ImageUploadStatus;
import com.callwise.voiceagent.service.EmailService;
import com.callwise.voiceagent.service.ImageUploadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestImageUploadToolTest {

    private ImageUploadService uploadService;
    private EmailService emailService;
    private RequestImageUploadTool tool;

    private UploadProperties properties;

    @BeforeEach
    void setUp() {
        uploadService = mock(ImageUploadService.class);
        emailService = mock(EmailService.class);
        properties = new UploadProperties();
        properties.setPublicBaseUrl("https://callwise.test");
        properties.setTokenTtlMinutes(30);
        tool = new RequestImageUploadTool(uploadService, emailService, properties, new ObjectMapper(), 8080);
        ToolContext.set("CA123", 7L);
    }

    @AfterEach
    void tearDown() {
        ToolContext.clear();
    }

    @Test
    void execute_happyPath_createsRowSendsEmailAndReturnsStatusSent() {
        ImageUpload row = ImageUpload.builder()
                .callSessionId(7L).email("a@x.invalid").token("tok-abc")
                .status(ImageUploadStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(30))
                .build();
        when(uploadService.createPendingUpload(eq(7L), eq("a@x.invalid"))).thenReturn(row);

        Map<String, Object> input = new HashMap<>();
        input.put("email", "a@x.invalid");
        input.put("reason", "to see the leak");

        String result = tool.execute(input);

        assertThat(result).contains("\"status\":\"sent\"")
                .contains("\"email\":\"a@x.invalid\"")
                .contains("\"expires_in_minutes\":30");
        verify(emailService).sendUploadLink(
                eq("a@x.invalid"),
                eq("https://callwise.test/uploads/tok-abc"),
                eq("to see the leak"),
                eq(30));
    }

    @Test
    void execute_invalidEmail_returnsErrorAndDoesNotCallServices() {
        Map<String, Object> input = Map.of("email", "not-an-email", "reason", "blah");

        String result = tool.execute(input);

        assertThat(result).contains("\"error\":\"invalid_email\"");
        verify(uploadService, never()).createPendingUpload(any(), any());
        verify(emailService, never()).sendUploadLink(any(), any(), any(), anyInt());
    }

    @Test
    void execute_noCallContext_returnsErrorEnvelope() {
        ToolContext.clear();
        Map<String, Object> input = Map.of("email", "a@x.invalid", "reason", "blah");

        String result = tool.execute(input);

        assertThat(result).contains("no_call_context");
    }

    @Test
    void execute_emptyPublicBaseUrl_defaultsToLocalhostInsteadOfRelative() {
        properties.setPublicBaseUrl("");
        ImageUpload row = ImageUpload.builder()
                .callSessionId(7L).email("a@x.invalid").token("tok-zzz")
                .status(ImageUploadStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(30))
                .build();
        when(uploadService.createPendingUpload(any(), any())).thenReturn(row);

        tool.execute(Map.of("email", "a@x.invalid", "reason", "blah"));

        verify(emailService).sendUploadLink(
                eq("a@x.invalid"),
                eq("http://localhost:8080/uploads/tok-zzz"),
                eq("blah"),
                eq(30));
    }

    @Test
    void execute_emailServiceThrows_propagatesAsErrorEnvelope() {
        ImageUpload row = ImageUpload.builder()
                .callSessionId(7L).email("a@x.invalid").token("tok-x")
                .status(ImageUploadStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(30))
                .build();
        when(uploadService.createPendingUpload(any(), any())).thenReturn(row);
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(emailService).sendUploadLink(any(), any(), any(), anyInt());

        String result = tool.execute(Map.of("email", "a@x.invalid", "reason", "r"));

        assertThat(result).contains("email_send_failed").contains("smtp down");
    }
}
