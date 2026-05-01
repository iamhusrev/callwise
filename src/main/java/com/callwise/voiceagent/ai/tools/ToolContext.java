package com.callwise.voiceagent.ai.tools;

/**
 * Per-thread context for tool execution. Lets a tool reach the current call's
 * {@code callSid} / session id without threading them through the {@link Tool#execute} signature.
 *
 * <p>Set by {@link com.callwise.voiceagent.service.ConversationOrchestrator} before each tool
 * iteration; cleared in a {@code finally} block. {@link ThreadLocal} is acceptable here because
 * each Twilio webhook is handled on a single Tomcat worker thread for the duration of a turn.
 */
public final class ToolContext {

    private static final ThreadLocal<String> CALL_SID = new ThreadLocal<>();
    private static final ThreadLocal<Long> SESSION_ID = new ThreadLocal<>();

    private ToolContext() {}

    public static void set(String callSid, Long sessionId) {
        CALL_SID.set(callSid);
        SESSION_ID.set(sessionId);
    }

    public static String getCallSid() {
        return CALL_SID.get();
    }

    public static Long getSessionId() {
        return SESSION_ID.get();
    }

    public static void clear() {
        CALL_SID.remove();
        SESSION_ID.remove();
    }
}
