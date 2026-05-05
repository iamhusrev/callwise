package com.callwise.voiceagent.ai.dto;

/**
 * Provider-agnostic image attached to a chat request. Each provider maps it into its
 * native multi-modal shape:
 *
 * <ul>
 *   <li>Anthropic: {@code {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"..."}}}</li>
 *   <li>OpenAI / Groq: {@code {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}}</li>
 * </ul>
 *
 * @param mediaType IANA media type (e.g. {@code image/jpeg}, {@code image/png}, {@code image/webp})
 * @param base64Data raw image bytes encoded as Base64 (no {@code data:} prefix)
 */
public record ImageContent(String mediaType, String base64Data) {

    public ImageContent {
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType must not be blank");
        }
        if (base64Data == null || base64Data.isBlank()) {
            throw new IllegalArgumentException("base64Data must not be blank");
        }
    }
}
