package com.callwise.voiceagent.controller;

import com.callwise.voiceagent.config.UploadProperties;
import com.callwise.voiceagent.exception.UploadTokenInvalidException;
import com.callwise.voiceagent.service.ImageUploadService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Tier 3 — public-facing upload endpoint. The token in the URL is the only authn signal,
 * which is why the {@link ImageUploadService} validates it strictly (no such token / expired
 * / already used) and the token itself comes from a 192-bit {@code SecureRandom}.
 *
 * <p>This is a {@link Controller} (not REST) because it serves Thymeleaf views to a browser.
 */
@Controller
@RequestMapping("/uploads")
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    private final ImageUploadService uploadService;
    private final UploadProperties properties;

    public UploadController(ImageUploadService uploadService, UploadProperties properties) {
        this.uploadService = uploadService;
        this.properties = properties;
    }

    @GetMapping("/{token}")
    public String form(@PathVariable String token, Model model) {
        // Throws UploadTokenInvalidException if token isn't a fresh PENDING row.
        uploadService.findValidPending(token);
        model.addAttribute("token", token);
        model.addAttribute("maxMb", Math.max(1, properties.getMaxBytes() / (1024 * 1024)));
        return "upload-form";
    }

    @PostMapping("/{token}")
    public String upload(
            @PathVariable String token,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        uploadService.storeImage(token, file);
        return "upload-success";
    }

    /* ---------- per-controller error handlers (HTML responses, not JSON) ---------- */

    @ExceptionHandler(UploadTokenInvalidException.class)
    public String handleInvalidToken(UploadTokenInvalidException e, Model model, HttpServletResponse response) {
        HttpStatus status = switch (e.getReason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case EXPIRED, ALREADY_USED -> HttpStatus.GONE;
        };
        response.setStatus(status.value());
        model.addAttribute("message", e.getMessage());
        log.warn("upload.invalid reason={} msg={}", e.getReason(), e.getMessage());
        return "upload-error";
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, IllegalArgumentException.class})
    public String handleBadInput(Exception e, Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("message", e.getMessage() == null ? "invalid upload" : e.getMessage());
        log.warn("upload.bad-input msg={}", e.getMessage());
        return "upload-error";
    }
}
