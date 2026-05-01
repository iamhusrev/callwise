package com.callwise.voiceagent.exception;

import com.callwise.voiceagent.service.TwiMLBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Centralised exception handling so the voice path always returns valid TwiML even on the
 * worst day of the AI providers' lives — Twilio drops the call if it gets non-XML on /voice/*.
 *
 * <p>Non-voice routes (admin) get a normal JSON error envelope.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final TwiMLBuilder twiMLBuilder;

    public GlobalExceptionHandler(TwiMLBuilder twiMLBuilder) {
        this.twiMLBuilder = twiMLBuilder;
    }

    @ExceptionHandler(AllProvidersFailedException.class)
    public ResponseEntity<String> handleAllProvidersFailed(AllProvidersFailedException e) {
        log.error("handler.all-providers-failed: {}", e.getMessage());
        String twiml = twiMLBuilder.buildHangup(
                "I'm sorry, I'm having trouble right now. Please call back in a few minutes."
        );
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(twiml);
    }

    /** Catch-all so an unexpected error in /voice/* still returns parseable TwiML. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception e) {
        log.error("handler.unexpected", e);
        // We can't reliably tell here whether the request was /voice/* or /admin/*, so return JSON
        // — in the voice path, AllProvidersFailedException above would have caught the typical
        // failure mode first. Anything reaching this handler is a programming error worth a 500.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "internal_error", "message", String.valueOf(e.getMessage())));
    }
}
