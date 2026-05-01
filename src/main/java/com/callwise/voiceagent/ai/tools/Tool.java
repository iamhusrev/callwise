package com.callwise.voiceagent.ai.tools;

import com.callwise.voiceagent.ai.dto.ToolDefinition;

import java.util.Map;

/**
 * A function-calling tool the AI can invoke.
 *
 * <p>Implementations are stateless Spring beans. State for the current call is read from
 * {@link ToolContext} (e.g. callSid).
 */
public interface Tool {

    /** snake_case name. Must match {@link ToolDefinition#name()}. */
    String getName();

    /** Schema sent to the AI provider. */
    ToolDefinition getDefinition();

    /**
     * Execute the tool with parsed inputs.
     *
     * @param input parameters parsed from the AI's tool_use block
     * @return JSON-serialised result. Anthropic and OpenAI both accept the result as a string.
     */
    String execute(Map<String, Object> input);
}
