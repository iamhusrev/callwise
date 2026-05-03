package com.callwise.voiceagent.ai;

import com.callwise.voiceagent.ai.dto.ChatResponse;
import com.callwise.voiceagent.ai.dto.VisionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the prompt-output parser used by {@link AIDispatcher#analyzeImage}. We
 * exercise the parser directly via the package-private static helper so we don't have to
 * stand up the full Spring context.
 */
class AIDispatcherVisionTest {

    @Test
    void parseVisionResponse_threeLineHappyPath_extractsAllFields() {
        ChatResponse r = textResp(
                "Appliance: washer\nVisible issues: water on floor, rust around drum\nSuggested next step: Inspect the door seal.",
                "claude");

        VisionResult v = AIDispatcher.parseVisionResponse(r);

        assertThat(v.applianceType()).isEqualTo("washer");
        assertThat(v.visibleIssues()).containsExactly("water on floor", "rust around drum");
        assertThat(v.suggestedNextStep()).isEqualTo("Inspect the door seal.");
        assertThat(v.providerName()).isEqualTo("claude");
        assertThat(v.rawText()).contains("Appliance:");
    }

    @Test
    void parseVisionResponse_noVisibleIssues_returnsEmptyList() {
        ChatResponse r = textResp("Appliance: dishwasher\nVisible issues: none visible\nSuggested next step: Run a clean cycle.", "claude");
        VisionResult v = AIDispatcher.parseVisionResponse(r);
        assertThat(v.applianceType()).isEqualTo("dishwasher");
        assertThat(v.visibleIssues()).isEmpty();
        assertThat(v.suggestedNextStep()).isEqualTo("Run a clean cycle.");
    }

    @Test
    void parseVisionResponse_emptyText_falsBackToUnknown() {
        ChatResponse r = textResp("", "groq");
        VisionResult v = AIDispatcher.parseVisionResponse(r);
        assertThat(v.applianceType()).isEqualTo("unknown");
        assertThat(v.visibleIssues()).isEmpty();
    }

    @Test
    void parseVisionResponse_messyCasing_isToleratedAndLowercased() {
        ChatResponse r = textResp("APPLIANCE: REFRIGERATOR\nvisible issues: bent door, scratched panel", "groq");
        VisionResult v = AIDispatcher.parseVisionResponse(r);
        assertThat(v.applianceType()).isEqualTo("refrigerator");
        assertThat(v.visibleIssues()).containsExactly("bent door", "scratched panel");
    }

    private static ChatResponse textResp(String text, String provider) {
        return new ChatResponse(text, List.of(), "end_turn", provider, 100L, 5, 5, 0);
    }
}
