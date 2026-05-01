package com.callwise.voiceagent.ai.dto;

import java.util.List;

/**
 * Provider-agnostic response.
 *
 * @param text             assistant text output (null when the response is a pure tool_use)
 * @param toolCalls        tools the AI wants to invoke (null/empty when no tool use)
 * @param stopReason       provider's stop reason: "end_turn", "tool_use", "max_tokens", etc.
 * @param providerName     "claude" / "groq" — which provider produced this response
 * @param latencyMs        wall-clock time of the HTTP call
 * @param inputTokens      tokens billed at full input rate
 * @param outputTokens     tokens billed at output rate
 * @param cachedInputTokens tokens billed at the cheaper cache-read rate (Anthropic-only for now)
 */
public record ChatResponse(
        String text,
        List<ToolCall> toolCalls,
        String stopReason,
        String providerName,
        long latencyMs,
        int inputTokens,
        int outputTokens,
        int cachedInputTokens
) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
