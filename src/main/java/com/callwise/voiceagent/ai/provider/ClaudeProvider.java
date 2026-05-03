package com.callwise.voiceagent.ai.provider;

import com.callwise.voiceagent.ai.dto.ChatRequest;
import com.callwise.voiceagent.ai.dto.ChatResponse;
import com.callwise.voiceagent.ai.dto.ImageContent;
import com.callwise.voiceagent.ai.dto.Message;
import com.callwise.voiceagent.ai.dto.ToolCall;
import com.callwise.voiceagent.ai.dto.ToolDefinition;
import com.callwise.voiceagent.exception.ProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API provider (claude-haiku-4-5 by default).
 *
 * <p>Wire-level details: see {@code POST https://api.anthropic.com/v1/messages}. We hit the
 * REST endpoint directly via Spring's {@link RestClient} rather than the official Java SDK
 * because the same abstraction also has to fit Groq's OpenAI-compatible API in Phase 3 —
 * keeping both providers on the same HTTP plumbing makes the multi-provider story uniform.
 *
 * <p>Prompt caching: we attach {@code cache_control: ephemeral} to the system block. Anthropic
 * silently skips caching below the model's minimum prefix size (4096 tokens for Haiku 4.5) —
 * no error, just {@code cache_creation_input_tokens=0}. As the conversation grows past the
 * threshold the read kicks in automatically.
 */
@Component
public class ClaudeProvider extends BaseProvider {

    public static final String NAME = "claude";

    private static final Logger log = LoggerFactory.getLogger(ClaudeProvider.class);

    private final RestClient claudeRestClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final String visionModel;

    public ClaudeProvider(
            @Qualifier("claudeRestClient") RestClient claudeRestClient,
            ObjectMapper objectMapper,
            @Value("${anthropic.model:claude-haiku-4-5}") String model,
            @Value("${callwise.vision.claude-model:claude-haiku-4-5}") String visionModel
    ) {
        this.claudeRestClient = claudeRestClient;
        this.objectMapper = objectMapper;
        this.model = model;
        this.visionModel = visionModel;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isHealthy() {
        // We don't ping Anthropic for health — too expensive. AIDispatcher's CircuitBreaker
        // is the source of truth; this method exists for the admin endpoint.
        return true;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        Map<String, Object> body = buildRequestBody(request);
        long startNanos = System.nanoTime();

        try {
            JsonNode response = claudeRestClient.post()
                    .uri("/v1/messages")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            long latencyMs = elapsedMs(startNanos);
            return parseResponse(response, latencyMs);

        } catch (HttpClientErrorException e) {
            // 4xx — bad request, auth, rate limit. No retry; fallback chain handles it.
            throw new ProviderException(NAME, "client error: " + e.getStatusText(), e.getStatusCode().value(), e);
        } catch (HttpServerErrorException e) {
            // 5xx — Anthropic transient. Same shape; dispatcher decides fallback.
            throw new ProviderException(NAME, "server error: " + e.getStatusText(), e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            // I/O / timeout
            throw new ProviderException(NAME, "I/O error: " + e.getMessage(), -1, e);
        } catch (Exception e) {
            throw new ProviderException(NAME, "unexpected: " + e.getMessage(), -1, e);
        }
    }

    /* ---------- request mapping ---------- */

    private Map<String, Object> buildRequestBody(ChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        // Switch to vision-capable model when images are attached. For Haiku 4.5 these are
        // the same id, but isolating the override keeps the door open for older deployments
        // where the chat model is text-only.
        body.put("model", request.hasImages() ? visionModel : model);
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());

        // System prompt as a list of text blocks so we can attach cache_control. Below the
        // 4096-token minimum the cache silently no-ops — that's fine, we still ship the same
        // shape so the moment a conversation grows past the threshold caching kicks in.
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            Map<String, Object> systemBlock = new LinkedHashMap<>();
            systemBlock.put("type", "text");
            systemBlock.put("text", request.systemPrompt());
            systemBlock.put("cache_control", Map.of("type", "ephemeral"));
            body.put("system", List.of(systemBlock));
        }

        body.put("messages", mapMessages(request.messages(), request.images()));

        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", mapTools(request.tools()));
        }
        return body;
    }

    private List<Map<String, Object>> mapMessages(List<Message> messages, List<ImageContent> images) {
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        // We attach images only to the LAST user message. Anthropic accepts multiple image
        // blocks per message and merges them with the surrounding text — exactly what we want
        // for "here is the photo, please describe it".
        int lastUserIdx = lastIndexOfRole(messages, "user");

        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            switch (m.role()) {
                case "user" -> {
                    if (i == lastUserIdx && images != null && !images.isEmpty()) {
                        List<Map<String, Object>> blocks = new ArrayList<>();
                        for (ImageContent img : images) {
                            blocks.add(Map.of(
                                    "type", "image",
                                    "source", Map.of(
                                            "type", "base64",
                                            "media_type", img.mediaType(),
                                            "data", img.base64Data()
                                    )
                            ));
                        }
                        if (m.content() != null && !m.content().isBlank()) {
                            blocks.add(Map.of("type", "text", "text", m.content()));
                        }
                        out.add(Map.of("role", "user", "content", blocks));
                    } else {
                        out.add(Map.of("role", "user", "content", m.content()));
                    }
                }
                case "assistant" -> {
                    if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                        // Replay an assistant turn that requested tools so Anthropic can match
                        // the upcoming "user" message containing the tool_result.
                        List<Map<String, Object>> blocks = new ArrayList<>();
                        if (m.content() != null && !m.content().isBlank()) {
                            blocks.add(Map.of("type", "text", "text", m.content()));
                        }
                        for (ToolCall tc : m.toolCalls()) {
                            blocks.add(Map.of(
                                    "type", "tool_use",
                                    "id", tc.id(),
                                    "name", tc.name(),
                                    "input", tc.input()
                            ));
                        }
                        out.add(Map.of("role", "assistant", "content", blocks));
                    } else {
                        out.add(Map.of("role", "assistant", "content", m.content()));
                    }
                }
                case "tool" -> {
                    // Anthropic models tool results as a "user" message with a tool_result block.
                    Map<String, Object> resultBlock = new LinkedHashMap<>();
                    resultBlock.put("type", "tool_result");
                    resultBlock.put("tool_use_id", m.toolCallId());
                    resultBlock.put("content", m.content());
                    out.add(Map.of("role", "user", "content", List.of(resultBlock)));
                }
                default -> throw new IllegalArgumentException("unsupported role: " + m.role());
            }
        }
        return out;
    }

    private static int lastIndexOfRole(List<Message> messages, String role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (role.equals(messages.get(i).role())) return i;
        }
        return -1;
    }

    private List<Map<String, Object>> mapTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> out = new ArrayList<>(tools.size());
        for (ToolDefinition t : tools) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", t.name());
            tool.put("description", t.description());
            tool.put("input_schema", t.inputSchema());
            out.add(tool);
        }
        return out;
    }

    /* ---------- response parsing ---------- */

    private ChatResponse parseResponse(JsonNode root, long latencyMs) {
        if (root == null) {
            throw new ProviderException(NAME, "empty response body");
        }

        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    if (text.length() > 0) text.append('\n');
                    text.append(block.path("text").asText());
                } else if ("tool_use".equals(type)) {
                    Map<String, Object> input = jsonObjectToMap(block.path("input"));
                    toolCalls.add(new ToolCall(
                            block.path("id").asText(),
                            block.path("name").asText(),
                            input
                    ));
                }
            }
        }

        String stopReason = root.path("stop_reason").asText("end_turn");
        JsonNode usage = root.path("usage");
        int inputTokens = usage.path("input_tokens").asInt(0);
        int outputTokens = usage.path("output_tokens").asInt(0);
        int cacheReadTokens = usage.path("cache_read_input_tokens").asInt(0);

        log.debug("claude.response stopReason={} latencyMs={} in={} out={} cached={}",
                stopReason, latencyMs, inputTokens, outputTokens, cacheReadTokens);

        return new ChatResponse(
                text.length() == 0 ? null : text.toString(),
                toolCalls.isEmpty() ? null : toolCalls,
                stopReason,
                NAME,
                latencyMs,
                inputTokens,
                outputTokens,
                cacheReadTokens
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonObjectToMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return Map.of();
        try {
            return objectMapper.convertValue(node, Map.class);
        } catch (IllegalArgumentException e) {
            log.warn("claude.parse failed to convert tool_use input: {}", e.getMessage());
            return Map.of();
        }
    }
}
