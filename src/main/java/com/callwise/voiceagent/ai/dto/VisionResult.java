package com.callwise.voiceagent.ai.dto;

import java.util.List;

/**
 * Parsed output of an image analysis call. The vision prompt asks the LLM to emit a
 * three-line structured summary; we extract the appliance and the visible-issues list,
 * keeping the raw text as a fallback for the AI to echo verbatim if needed.
 *
 * @param applianceType  best-effort appliance category (washer, dryer, refrigerator, ...) or "unknown"
 * @param visibleIssues  short bullet list of issues spotted; never null, may be empty
 * @param suggestedNextStep one-sentence hint; may be null
 * @param rawText        original LLM text (for diagnostics + AI consumption)
 * @param providerName   which provider answered ("claude" / "groq")
 */
public record VisionResult(
        String applianceType,
        List<String> visibleIssues,
        String suggestedNextStep,
        String rawText,
        String providerName
) {

    public VisionResult {
        if (visibleIssues == null) visibleIssues = List.of();
    }
}
