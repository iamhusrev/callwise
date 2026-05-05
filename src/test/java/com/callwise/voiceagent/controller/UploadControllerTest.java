package com.callwise.voiceagent.controller;

import com.callwise.voiceagent.config.UploadProperties;
import com.callwise.voiceagent.entity.ImageUpload;
import com.callwise.voiceagent.entity.ImageUploadStatus;
import com.callwise.voiceagent.exception.UploadTokenInvalidException;
import com.callwise.voiceagent.service.ImageUploadService;
import com.callwise.voiceagent.service.TwiMLBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Autowired;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Slice test for {@link UploadController} — Spring MVC + Thymeleaf only, no DB. Confirms the
 * happy/sad branches each render the expected view with the expected HTTP status.
 */
@WebMvcTest(UploadController.class)
@Import({ThymeleafAutoConfiguration.class, UploadProperties.class})
@TestPropertySource(properties = {
        "callwise.uploads.public-base-url=https://test.invalid",
        "callwise.uploads.token-ttl-minutes=30",
        "callwise.uploads.max-bytes=1048576"
})
class UploadControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ImageUploadService uploadService;
    // Required because @WebMvcTest auto-loads @ControllerAdvice (GlobalExceptionHandler),
    // which has a TwiMLBuilder dependency we don't actually exercise here.
    @MockBean TwiMLBuilder twiMLBuilder;

    private ImageUpload pending;

    @BeforeEach
    void setUp() {
        pending = ImageUpload.builder()
                .id(1L).callSessionId(7L).email("a@x.invalid").token("tok-ok")
                .status(ImageUploadStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(20))
                .build();
    }

    @Test
    void getForm_validToken_rendersUploadForm() throws Exception {
        when(uploadService.findValidPending("tok-ok")).thenReturn(pending);

        mockMvc.perform(get("/uploads/tok-ok"))
                .andExpect(status().isOk())
                .andExpect(view().name("upload-form"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Upload your appliance photo")));
    }

    @Test
    void getForm_unknownToken_returns404ErrorView() throws Exception {
        when(uploadService.findValidPending("nope"))
                .thenThrow(new UploadTokenInvalidException(UploadTokenInvalidException.Reason.NOT_FOUND, "no such token"));

        mockMvc.perform(get("/uploads/nope"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("upload-error"));
    }

    @Test
    void getForm_expiredToken_returns410() throws Exception {
        when(uploadService.findValidPending("expired"))
                .thenThrow(new UploadTokenInvalidException(UploadTokenInvalidException.Reason.EXPIRED, "expired"));

        mockMvc.perform(get("/uploads/expired"))
                .andExpect(status().isGone())
                .andExpect(view().name("upload-error"));
    }

    @Test
    void postUpload_happyPath_rendersSuccessView() throws Exception {
        when(uploadService.storeImage(eq("tok-ok"), any())).thenReturn(pending);
        MockMultipartFile file = new MockMultipartFile("file", "p.jpg",
                MediaType.IMAGE_JPEG_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/uploads/tok-ok").file(file))
                .andExpect(status().isOk())
                .andExpect(view().name("upload-success"));
    }

    @Test
    void postUpload_invalidArg_returns400ErrorView() throws Exception {
        doThrow(new IllegalArgumentException("unsupported content type: text/plain"))
                .when(uploadService).storeImage(eq("tok-ok"), any());
        MockMultipartFile file = new MockMultipartFile("file", "p.txt",
                "text/plain", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/uploads/tok-ok").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("upload-error"));
    }
}
