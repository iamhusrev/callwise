package com.callwise.voiceagent.ai.prompt;

import com.callwise.voiceagent.ai.dto.ChatRequest;
import com.callwise.voiceagent.ai.dto.Message;
import com.callwise.voiceagent.ai.dto.ToolCall;
import com.callwise.voiceagent.ai.dto.ToolDefinition;
import com.callwise.voiceagent.entity.ConversationMessage;
import com.callwise.voiceagent.repository.ConversationMessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads the system prompt once at startup and rebuilds the conversation prefix on every turn.
 *
 * <p>Caching the system prompt in a final field keeps the byte sequence stable — important for
 * Anthropic's prefix-match cache (any byte change in the prefix invalidates downstream).
 */
@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private final Resource systemPromptResource;
    private final ConversationMessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final double temperature;
    private final int maxTokens;

    private String systemPrompt;

    public PromptBuilder(
            @Value("classpath:prompts/system-prompt.txt") Resource systemPromptResource,
            ConversationMessageRepository messageRepository,
            ObjectMapper objectMapper,
            @Value("${conversation.temperature:0.7}") double temperature,
            @Value("${anthropic.max-tokens:1024}") int maxTokens
    ) {
        this.systemPromptResource = systemPromptResource;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    @PostConstruct
    void loadSystemPrompt() {
        try {
            this.systemPrompt = new String(
                    systemPromptResource.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            ).trim();
            log.info("prompt.loaded chars={}", systemPrompt.length());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load system prompt", e);
        }
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Reads stored {@link ConversationMessage} rows for the session and converts them to provider-
     * agnostic {@link Message} DTOs in chronological order. Skips any rows with a role we don't
     * recognise (defensive against a future schema change).
     */
    public List<Message> buildConversationMessages(Long sessionId) {
        List<ConversationMessage> rows = messageRepository.findByCallSessionIdOrderByTurnNumberAsc(sessionId);
        List<Message> messages = new ArrayList<>(rows.size());
        for (ConversationMessage row : rows) {
            switch (row.getRole()) {
                case "USER" -> messages.add(Message.user(row.getContent()));
                case "ASSISTANT" -> messages.add(Message.assistant(row.getContent()));
                case "TOOL_CALL" -> {
                    Map<String, Object> input = parseJsonObject(row.getToolInput());
                    ToolCall tc = new ToolCall(row.getToolUseId(), row.getToolName(), input);
                    messages.add(Message.assistantWithTools(row.getContent(), List.of(tc)));
                }
                case "TOOL_RESULT" -> messages.add(Message.tool(row.getToolUseId(), row.getToolOutput()));
                default -> log.warn("prompt.skip unknown role={}", row.getRole());
            }
        }
        return messages;
    }

    /**
     * Convenience: build the full ChatRequest for a turn.
     *
     * <p>If {@code userInput} is null or blank, returns history-only — used by the tool
     * execution loop, where the next AI call should follow the freshly-persisted tool result
     * without inserting a synthetic user message.
     */
    public ChatRequest buildRequest(Long sessionId, String userInput, List<ToolDefinition> tools) {
        List<Message> history = buildConversationMessages(sessionId);
        if (userInput != null && !userInput.isBlank()) {
            history.add(Message.user(userInput));
        }
        return new ChatRequest(history, systemPrompt, tools, temperature, maxTokens);
    }

    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            log.warn("prompt.parse failed for tool_input: {}", e.getMessage());
            return Map.of();
        }
    }
}
