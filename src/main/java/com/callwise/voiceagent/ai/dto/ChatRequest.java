package com.callwise.voiceagent.ai.dto;

import java.util.List;

/**
 * Provider-agnostic request to a chat-style LLM.
 *
 * @param messages     conversation history (does NOT include the system prompt — that lives separately)
 * @param systemPrompt frozen system prompt; kept first in render order so it can be prefix-cached
 * @param tools        function-calling tool definitions, or null/empty if not using tool use
 * @param temperature  sampling temperature (0.0 = deterministic, 1.0 = maximum variance)
 * @param maxTokens    upper bound on output tokens
 * @param images       optional image attachments for vision (Tier 3); attached to the LAST user message
 */
public record ChatRequest(
        List<Message> messages,
        String systemPrompt,
        List<ToolDefinition> tools,
        double temperature,
        int maxTokens,
        List<ImageContent> images
) {

    public ChatRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must be non-empty");
        }
        if (images == null) images = List.of();
    }

    /** Backwards-compatible 5-arg constructor for the text-only call sites. */
    public ChatRequest(
            List<Message> messages,
            String systemPrompt,
            List<ToolDefinition> tools,
            double temperature,
            int maxTokens
    ) {
        this(messages, systemPrompt, tools, temperature, maxTokens, List.of());
    }

    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }
}
