package com.callwise.voiceagent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity tests for {@link TwiMLBuilder}. The Twilio SDK does the actual XML building/escaping —
 * what we want to verify here is that we wire it up correctly (a Gather follows the Say, the
 * action URL is /voice/gather, special characters in user-influenced text don't break the XML).
 */
class TwiMLBuilderTest {

    private final TwiMLBuilder builder = new TwiMLBuilder();

    @Test
    void buildGreeting_emitsGatherWithCorrectActionAndFallbackHangup() {
        String xml = builder.buildGreeting();

        assertThat(xml)
                .startsWith("<?xml version=")
                .contains("<Gather")
                .contains("action=\"/voice/gather\"")
                .contains("method=\"POST\"")
                .contains("input=\"speech\"")
                .contains("Sears Home Services")
                .contains("<Hangup/>");
    }

    @Test
    void buildGather_wrapsMessageInSayAndFollowsWithGather() {
        String xml = builder.buildGather("Got it, what is the appliance?");

        assertThat(xml)
                .contains("Got it, what is the appliance?")
                .contains("<Gather")
                .contains("<Hangup/>");
    }

    @Test
    void buildGather_userTextWithXmlSpecialChars_isEscaped() {
        // Verifies the SDK actually escapes — if we ever switched to manual concat, this test
        // would catch it (CLAUDE.md: never construct TwiML by string concatenation).
        String xml = builder.buildGather("price < $50 & easy");

        assertThat(xml)
                .contains("&lt;")
                .contains("&amp;")
                .doesNotContain("price < $50 & easy");
    }

    @Test
    void buildHangup_includesMessageThenHangup() {
        String xml = builder.buildHangup("Thank you, goodbye.");

        assertThat(xml)
                .contains("Thank you, goodbye.")
                .contains("<Hangup/>");
    }
}
