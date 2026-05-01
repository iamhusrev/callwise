package com.callwise.voiceagent.ai.provider;

import com.callwise.voiceagent.ai.dto.ChatRequest;
import com.callwise.voiceagent.ai.dto.ChatResponse;
import com.callwise.voiceagent.ai.dto.Message;
import com.callwise.voiceagent.ai.dto.ToolCall;
import com.callwise.voiceagent.ai.dto.ToolDefinition;
import com.callwise.voiceagent.exception.ProviderException;
import com.fasterxml.jackson.core.type.TypeReference;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groq Chat Completions API (OpenAI-compatible) — Llama 3.3 70B by default.
 *
 * <p>Wire shape differs from Anthropic in three places:
 * <ul>
 *   <li>The system prompt is the FIRST element of {@code messages}, not a top-level field.</li>
 *   <li>Tools are wrapped in {@code {"type":"function","function":{...}}}; parameters live under {@code function.parameters}.</li>
 *   <li>Tool calls in the response live in {@code choices[0].message.tool_calls}; arguments
 *       arrive as a JSON-encoded STRING that must be parsed before use.</li>
 * </ul>
 */
@Component
public class GroqProvider extends BaseProvider {

    public static final String NAME = "groq";

    private static final Logger log = LoggerFactory.getLogger(GroqProvider.class);

    private final RestClient groqRestClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public GroqProvider(
            @Qualifier("groqRestClient") RestClient groqRestClient,
            ObjectMapper objectMapper,
            @Value("${groq.model:llama-3.3-70b-versatile}") String model
    ) {
        this.groqRestClient = groqRestClient;
        this.objectMapper = objectMapper;
        this.model = model;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        Map<String, Object> body = buildRequestBody(request);
        long startNanos = System.nanoTime();

        try {
            JsonNode response = groqRestClient.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            return parseResponse(response, elapsedMs(startNanos));

        } catch (HttpClientErrorException e) {
            throw new ProviderException(NAME, "client error: " + e.getStatusText(), e.getStatusCode().value(), e);
        } catch (HttpServerErrorException e) {
            throw new ProviderException(NAME, "server error: " + e.getStatusText(), e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new ProviderException(NAME, "I/O error: " + e.getMessage(), -1, e);
        } catch (Exception e) {
            throw new ProviderException(NAME, "unexpected: " + e.getMessage(), -1, e);
        }
    }

    /* ---------- request mapping ---------- */

    private Map<String, Object> buildRequestBody(ChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());

        // OpenAI-style: system goes inside messages as the first element.
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        for (Message m : request.messages()) {
            messages.add(mapOneMessage(m));
        }
        body.put("messages", messages);

        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", mapTools(request.tools()));
            body.put("tool_choice", "auto");
        }
        return body;
    }

    private Map<String, Object> mapOneMessage(Message m) {
        return switch (m.role()) {
            case "user" -> Map.of("role", "user", "content", m.content());
            case "assistant" -> {
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    List<Map<String, Object>> tcs = new ArrayList<>();
                    for (ToolCall tc : m.toolCalls()) {
                        tcs.add(Map.of(
                                "id", tc.id(),
                                "type", "function",
                                "function", Map.of(
                                        "name", tc.name(),
                                        "arguments", toJson(tc.input())
                                )
                        ));
                    }
                    Map<String, Object> assistant = new LinkedHashMap<>();
                    assistant.put("role", "assistant");
                    assistant.put("content", m.content() == null ? "" : m.content());
                    assistant.put("tool_calls", tcs);
                    yield assistant;
                }
                yield Map.of("role", "assistant", "content", m.content());
            }
            // OpenAI uses role="tool" with tool_call_id for tool results (different from Anthropic).
            case "tool" -> Map.of(
                    "role", "tool",
                    "tool_call_id", m.toolCallId() == null ? "" : m.toolCallId(),
                    "content", m.content() == null ? "" : m.content()
            );
            default -> throw new IllegalArgumentException("unsupported role: " + m.role());
        };
    }

    private List<Map<String, Object>> mapTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> out = new ArrayList<>(tools.size());
        for (ToolDefinition t : tools) {
            out.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", t.name(),
                            "description", t.description(),
                            "parameters", t.inputSchema()
                    )
            ));
        }
        return out;
    }

    /* ---------- response parsing ---------- */

    private ChatResponse parseResponse(JsonNode root, long latencyMs) {
        if (root == null) {
            throw new ProviderException(NAME, "empty response body");
        }

        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");

        String text = null;
        JsonNode contentNode = message.path("content");
        if (contentNode.isTextual() && !contentNode.asText().isBlank()) {
            text = contentNode.asText();
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode toolCallsNode = message.path("tool_calls");
        if (toolCallsNode.isArray()) {
            for (JsonNode tc : toolCallsNode) {
                String id = tc.path("id").asText();
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText();
                String argsJson = fn.path("arguments").asText("{}");
                Map<String, Object> args = parseArguments(argsJson);
                toolCalls.add(new ToolCall(id, name, args));
            }
        }

        String finishReason = choice.path("finish_reason").asText("stop");
        // Map OpenAI-style finish_reason to our provider-agnostic names where it matters.
        String stopReason = switch (finishReason) {
            case "stop" -> "end_turn";
            case "tool_calls" -> "tool_use";
            case "length" -> "max_tokens";
            default -> finishReason;
        };

        JsonNode usage = root.path("usage");
        int inputTokens = usage.path("prompt_tokens").asInt(0);
        int outputTokens = usage.path("completion_tokens").asInt(0);

        log.debug("groq.response stopReason={} latencyMs={} in={} out={}",
                stopReason, latencyMs, inputTokens, outputTokens);

        return new ChatResponse(
                text,
                toolCalls.isEmpty() ? null : toolCalls,
                stopReason,
                NAME,
                latencyMs,
                inputTokens,
                outputTokens,
                0  // Groq does not surface a cache-hit token count today
        );
    }

    private String toJson(Map<String, Object> input) {
        try {
            return objectMapper.writeValueAsString(input == null ? Map.of() : input);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            log.warn("groq.parse failed to parse tool arguments: {}", e.getMessage());
            return Map.of();
        }
    }
}
