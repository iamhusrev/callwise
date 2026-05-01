package com.callwise.voiceagent.ai.dto;

import java.util.List;

/**
 * Provider-agnostic message in a conversation.
 *
 * @param role        one of: "system", "user", "assistant", "tool"
 * @param content     text content (null for assistant messages that only contain tool calls)
 * @param toolCallId  set on role="tool" messages, references the assistant's tool_use id
 * @param toolCalls   set on role="assistant" messages that invoke tools
 */
public record Message(
        String role,
        String content,
        String toolCallId,
        List<ToolCall> toolCalls
) {

    public static Message system(String content) {
        return new Message("system", content, null, null);
    }

    public static Message user(String content) {
        return new Message("user", content, null, null);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content, null, null);
    }

    public static Message assistantWithTools(String content, List<ToolCall> toolCalls) {
        return new Message("assistant", content, null, toolCalls);
    }

    public static Message tool(String toolCallId, String result) {
        return new Message("tool", result, toolCallId, null);
    }
}
