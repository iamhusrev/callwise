package com.callwise.voiceagent.controller;

import com.callwise.voiceagent.service.ConversationOrchestrator;
import com.callwise.voiceagent.service.TwiMLBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Twilio webhook endpoints. Phase 2: routes user speech through the conversation orchestrator
 * (which calls Claude). Twilio sends form-urlencoded webhooks; we return TwiML XML.
 */
@RestController
@RequestMapping("/voice")
public class TwilioVoiceController {

    private static final Logger log = LoggerFactory.getLogger(TwilioVoiceController.class);

    private final ConversationOrchestrator orchestrator;
    private final TwiMLBuilder twiMLBuilder;

    public TwilioVoiceController(ConversationOrchestrator orchestrator, TwiMLBuilder twiMLBuilder) {
        this.orchestrator = orchestrator;
        this.twiMLBuilder = twiMLBuilder;
    }

    @PostMapping(
            value = "/incoming",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE
    )
    public String incoming(
            @RequestParam("CallSid") String callSid,
            @RequestParam(value = "From", required = false, defaultValue = "unknown") String from,
            @RequestParam(value = "To", required = false) String to
    ) {
        log.info("voice.incoming callSid={} from={} to={}", callSid, from, to);
        return orchestrator.handleNewCall(callSid, from);
    }

    @PostMapping(
            value = "/gather",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE
    )
    public String gather(
            @RequestParam("CallSid") String callSid,
            @RequestParam(value = "From", required = false, defaultValue = "unknown") String from,
            @RequestParam(value = "SpeechResult", required = false, defaultValue = "") String speechResult,
            @RequestParam(value = "Confidence", required = false) String confidence
    ) {
        log.info("voice.gather callSid={} confidence={} hasSpeech={}",
                callSid, confidence, !speechResult.isBlank());
        if (speechResult.isBlank()) {
            // Silence — re-prompt without an LLM round trip. Phase 7 adds a counter for repeated silence.
            return twiMLBuilder.buildGather("I didn't catch that. Could you say it again?");
        }
        return orchestrator.handleUserInput(callSid, from, speechResult);
    }

    @PostMapping(
            value = "/status",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public void status(
            @RequestParam("CallSid") String callSid,
            @RequestParam(value = "CallStatus", required = false, defaultValue = "unknown") String callStatus,
            @RequestParam(value = "CallDuration", required = false) String callDuration
    ) {
        log.info("voice.status callSid={} status={} duration={}", callSid, callStatus, callDuration);
        orchestrator.handleCallEnd(callSid, callStatus);
    }
}
