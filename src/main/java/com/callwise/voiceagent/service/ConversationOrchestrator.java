package com.callwise.voiceagent.service;

import com.callwise.voiceagent.ai.AIDispatcher;
import com.callwise.voiceagent.ai.dto.ChatRequest;
import com.callwise.voiceagent.ai.dto.ChatResponse;
import com.callwise.voiceagent.ai.dto.Message;
import com.callwise.voiceagent.ai.dto.ToolCall;
import com.callwise.voiceagent.ai.prompt.PromptBuilder;
import com.callwise.voiceagent.ai.tools.ToolContext;
import com.callwise.voiceagent.ai.tools.ToolRegistry;
import com.callwise.voiceagent.entity.CallSession;
import com.callwise.voiceagent.entity.ConversationMessage;
import com.callwise.voiceagent.exception.AllProvidersFailedException;
import com.callwise.voiceagent.repository.ConversationMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 3 orchestrator: routes turns through {@link AIDispatcher} (multi-provider with
 * circuit breakers) and runs the tool execution loop.
 *
 * <p>Tool loop invariants:
 * <ul>
 *   <li>Max {@value #MAX_TOOL_ITERATIONS} iterations per user input — prevents the AI from
 *       looping on a tool that keeps returning the same result.</li>
 *   <li>Each tool_use is persisted as a TOOL_CALL row before execution and a TOOL_RESULT row
 *       after, both keyed by the provider's {@code tool_use_id} so {@link PromptBuilder} can
 *       reconstruct the exchange on the next turn.</li>
 *   <li>{@link ToolContext} is populated before each tool execution and cleared in finally —
 *       lets schedule tools reach the current callSid without polluting the Tool API.</li>
 * </ul>
 */
@Service
public class ConversationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ConversationOrchestrator.class);
    private static final int MAX_TOOL_ITERATIONS = 3;

    private final ConversationService conversationService;
    private final ConversationMessageRepository messageRepository;
    private final PromptBuilder promptBuilder;
    private final AIDispatcher aiDispatcher;
    private final ToolRegistry toolRegistry;
    private final TwiMLBuilder twiMLBuilder;
    private final ObjectMapper objectMapper;
    private final ObservabilityService observability;

    public ConversationOrchestrator(
            ConversationService conversationService,
            ConversationMessageRepository messageRepository,
            PromptBuilder promptBuilder,
            AIDispatcher aiDispatcher,
            ToolRegistry toolRegistry,
            TwiMLBuilder twiMLBuilder,
            ObjectMapper objectMapper,
            ObservabilityService observability
    ) {
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.promptBuilder = promptBuilder;
        this.aiDispatcher = aiDispatcher;
        this.toolRegistry = toolRegistry;
        this.twiMLBuilder = twiMLBuilder;
        this.objectMapper = objectMapper;
        this.observability = observability;
    }

    @Transactional
    public String handleNewCall(String callSid, String phoneNumber) {
        conversationService.getOrCreateSession(callSid, phoneNumber);
        log.info("orchestrator.new-call callSid={}", callSid);
        return twiMLBuilder.buildGreeting();
    }

    @Transactional
    public String handleUserInput(String callSid, String phoneNumber, String userInput) {
        CallSession session = conversationService.getOrCreateSession(callSid, phoneNumber);
        Long sessionId = session.getId();

        int turn = nextTurnNumber(sessionId);
        saveMessage(sessionId, turn, "USER", userInput, null, null, null, null);

        try {
            ToolContext.set(callSid, sessionId);
            observability.bindTurn(callSid, turn);

            ChatRequest request = promptBuilder.buildRequest(sessionId, userInput, toolRegistry.getAllDefinitions());
            ChatResponse response = aiDispatcher.diagnose(request);
            turn++;

            // Tool execution loop. Bounded by MAX_TOOL_ITERATIONS to defend against the AI
            // calling the same tool repeatedly (Phase 7 will also detect identical input loops).
            int iterations = 0;
            while (response.hasToolCalls() && iterations < MAX_TOOL_ITERATIONS) {
                iterations++;
                turn = persistToolUse(sessionId, turn, response);
                turn = executeAndPersistResults(sessionId, turn, response.toolCalls());

                // Re-call the AI with the freshly-persisted tool results visible in the history.
                // userInput=null tells PromptBuilder not to append a synthetic user turn after
                // the tool_result rows.
                request = promptBuilder.buildRequest(sessionId, null, toolRegistry.getAllDefinitions());
                response = aiDispatcher.diagnose(request);
                turn++;
            }

            String assistantText = response.text() == null ? "" : response.text().trim();
            if (assistantText.isBlank()) {
                assistantText = "Could you tell me a bit more about the problem?";
            }
            saveMessage(sessionId, turn, "ASSISTANT", assistantText, null, null, null, null);

            log.info("orchestrator.turn callSid={} latencyMs={} provider={} toolIterations={} stop={}",
                    callSid, response.latencyMs(), response.providerName(), iterations, response.stopReason());

            return twiMLBuilder.buildGather(assistantText);

        } catch (AllProvidersFailedException e) {
            log.error("orchestrator.all-providers-failed callSid={}", callSid, e);
            conversationService.markFailed(callSid);
            return twiMLBuilder.buildHangup(
                    "I'm having trouble connecting right now. Please try calling back in a few minutes."
            );
        } finally {
            ToolContext.clear();
            observability.clearTurn();
        }
    }

    @Transactional
    public void handleCallEnd(String callSid, String callStatus) {
        if ("completed".equalsIgnoreCase(callStatus)) {
            conversationService.markCompleted(callSid);
        } else if ("failed".equalsIgnoreCase(callStatus) || "no-answer".equalsIgnoreCase(callStatus)) {
            conversationService.markFailed(callSid);
        } else {
            log.debug("orchestrator.call-end ignored status={} callSid={}", callStatus, callSid);
        }
    }

    /* ---------- tool loop helpers ---------- */

    private int persistToolUse(Long sessionId, int turn, ChatResponse response) {
        // Attach the assistant's text only to the first row so parallel tool calls don't
        // produce duplicated text on replay.
        boolean first = true;
        for (ToolCall tc : response.toolCalls()) {
            saveMessage(
                    sessionId, turn, "TOOL_CALL",
                    first ? response.text() : null,
                    tc.name(),
                    tc.id(),
                    serialize(tc.input()),
                    null
            );
            turn++;
            first = false;
        }
        return turn;
    }

    private int executeAndPersistResults(Long sessionId, int turn, List<ToolCall> toolCalls) {
        for (ToolCall tc : toolCalls) {
            String result = toolRegistry.execute(tc.name(), tc.input());
            saveMessage(sessionId, turn, "TOOL_RESULT", null, tc.name(), tc.id(), null, result);
            turn++;
        }
        return turn;
    }

    /* ---------- common helpers ---------- */

    private int nextTurnNumber(Long sessionId) {
        Integer max = messageRepository.findMaxTurnNumber(sessionId);
        return max == null ? 1 : max + 1;
    }

    private void saveMessage(
            Long sessionId, int turnNumber, String role, String content,
            String toolName, String toolUseId, String toolInput, String toolOutput
    ) {
        ConversationMessage msg = ConversationMessage.builder()
                .callSessionId(sessionId)
                .turnNumber(turnNumber)
                .role(role)
                .content(content)
                .toolName(toolName)
                .toolUseId(toolUseId)
                .toolInput(toolInput)
                .toolOutput(toolOutput)
                .build();
        messageRepository.save(msg);
    }

    private String serialize(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("orchestrator.serialize failed: {}", e.getMessage());
            return "{}";
        }
    }
}
