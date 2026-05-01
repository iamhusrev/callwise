package com.callwise.voiceagent.ai.dto;

import java.util.Map;

/**
 * A tool/function invocation requested by the AI.
 *
 * @param id     provider-issued unique id (e.g. Anthropic's "toolu_..."). Used to correlate
 *               the eventual tool result back to this call.
 * @param name   the registered tool name (e.g. "find_technicians")
 * @param input  the parsed arguments the AI wants the tool to execute with
 */
public record ToolCall(String id, String name, Map<String, Object> input) {
}
