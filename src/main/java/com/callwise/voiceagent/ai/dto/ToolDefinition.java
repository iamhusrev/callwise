package com.callwise.voiceagent.ai.dto;

import java.util.Map;

/**
 * Provider-agnostic description of a function-calling tool.
 *
 * <p>Concrete providers (Anthropic, Groq) wrap this in their own envelope before sending —
 * Anthropic uses {@code input_schema}, OpenAI-compatible APIs use {@code function.parameters}.
 *
 * @param name         tool name (snake_case, must match {@link com.callwise.voiceagent.ai.tools.Tool#getName()})
 * @param description  human-readable description; AI uses this to decide when to call the tool
 * @param inputSchema  JSON Schema object describing the parameters
 */
public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
}
