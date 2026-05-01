package com.callwise.voiceagent.service;

import com.twilio.http.HttpMethod;
import com.twilio.twiml.TwiMLException;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Gather;
import com.twilio.twiml.voice.Hangup;
import com.twilio.twiml.voice.Say;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds TwiML responses using the Twilio SDK builder.
 *
 * <p>The SDK escapes XML special characters automatically; constructing TwiML by string
 * concatenation is forbidden by project conventions because user speech transcripts may
 * contain unsafe characters (CLAUDE.md Twilio rule).
 */
@Service
public class TwiMLBuilder {

    private static final String GATHER_ACTION_URL = "/voice/gather";
    // Caller silence tolerance before falling back to "didn't hear anything".
    // speechTimeout=auto (below) already handles end-of-speech via VAD, so this
    // value only affects the wait for speech to *start* — be generous so callers
    // can pause to think without the call hanging up on them.
    private static final int GATHER_TIMEOUT_SECONDS = 8;
    private static final String GATHER_SPEECH_TIMEOUT = "auto";

    /**
     * Initial greeting + first <Gather> for the caller's response.
     */
    public String buildGreeting() {
        Gather gather = new Gather.Builder()
                .inputs(List.of(Gather.Input.SPEECH))
                .action(GATHER_ACTION_URL)
                .method(HttpMethod.POST)
                .timeout(GATHER_TIMEOUT_SECONDS)
                .speechTimeout(GATHER_SPEECH_TIMEOUT)
                .say(new Say.Builder("Hello, this is Sears Home Services. How can I help you today?").build())
                .build();

        VoiceResponse response = new VoiceResponse.Builder()
                .gather(gather)
                .say(new Say.Builder("I didn't hear anything. Please call back.").build())
                .hangup(new Hangup.Builder().build())
                .build();

        return toXml(response);
    }

    /**
     * Speak a message and gather the next caller utterance.
     */
    public String buildGather(String message) {
        Gather gather = new Gather.Builder()
                .inputs(List.of(Gather.Input.SPEECH))
                .action(GATHER_ACTION_URL)
                .method(HttpMethod.POST)
                .timeout(GATHER_TIMEOUT_SECONDS)
                .speechTimeout(GATHER_SPEECH_TIMEOUT)
                .say(new Say.Builder(message).build())
                .build();

        VoiceResponse response = new VoiceResponse.Builder()
                .gather(gather)
                .say(new Say.Builder("I didn't catch that. Please call back.").build())
                .hangup(new Hangup.Builder().build())
                .build();

        return toXml(response);
    }

    /**
     * Echo helper used by the Phase 1 hello-world stub before the AI is wired in.
     */
    public String buildEcho(String userInput) {
        VoiceResponse response = new VoiceResponse.Builder()
                .say(new Say.Builder("You said: " + userInput).build())
                .hangup(new Hangup.Builder().build())
                .build();
        return toXml(response);
    }

    /**
     * Final message + hangup. Used for graceful failures and completed conversations.
     */
    public String buildHangup(String message) {
        VoiceResponse response = new VoiceResponse.Builder()
                .say(new Say.Builder(message).build())
                .hangup(new Hangup.Builder().build())
                .build();
        return toXml(response);
    }

    private String toXml(VoiceResponse response) {
        try {
            return response.toXml();
        } catch (TwiMLException e) {
            // The SDK only throws this on internal serialization errors. If it ever fires,
            // we fall back to a minimal valid TwiML so the call doesn't drop unexpectedly.
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say>System error. Please call back.</Say><Hangup/></Response>";
        }
    }
}
