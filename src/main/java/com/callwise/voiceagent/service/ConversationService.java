package com.callwise.voiceagent.service;

import com.callwise.voiceagent.entity.CallSession;
import com.callwise.voiceagent.repository.CallSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * State management for call sessions.
 *
 * <p>Twilio retries webhooks on transient failures; idempotency by callSid is required.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final CallSessionRepository callSessionRepository;

    public ConversationService(CallSessionRepository callSessionRepository) {
        this.callSessionRepository = callSessionRepository;
    }

    /**
     * Returns the existing CallSession for {@code callSid}, or creates a new ACTIVE one.
     */
    @Transactional
    public CallSession getOrCreateSession(String callSid, String phoneNumber) {
        return callSessionRepository.findByCallSid(callSid)
                .orElseGet(() -> {
                    CallSession created = CallSession.builder()
                            .callSid(callSid)
                            .phoneNumber(phoneNumber)
                            .status("ACTIVE")
                            .build();
                    CallSession saved = callSessionRepository.save(created);
                    log.info("call.session.created callSid={} phoneNumber={}", callSid, phoneNumber);
                    return saved;
                });
    }

    /**
     * Marks a session as completed when Twilio fires the call status callback.
     */
    @Transactional
    public void markCompleted(String callSid) {
        callSessionRepository.findByCallSid(callSid).ifPresent(session -> {
            session.setStatus("COMPLETED");
            session.setEndedAt(OffsetDateTime.now());
            log.info("call.session.completed callSid={}", callSid);
        });
    }

    /**
     * Marks a session FAILED on unrecoverable errors (e.g., all AI providers down).
     */
    @Transactional
    public void markFailed(String callSid) {
        callSessionRepository.findByCallSid(callSid).ifPresent(session -> {
            session.setStatus("FAILED");
            session.setEndedAt(OffsetDateTime.now());
            log.warn("call.session.failed callSid={}", callSid);
        });
    }

    /**
     * Idempotent back-fill of the structured {@code appliance_type} on the parent session,
     * called from the find_technicians tool the first time the AI confirms the appliance.
     * Subsequent invocations within the same call are no-ops, so admin views surface a
     * stable category without overwriting earlier classifications.
     */
    @Transactional
    public void recordApplianceType(Long sessionId, String applianceType) {
        if (sessionId == null || applianceType == null || applianceType.isBlank()) return;
        callSessionRepository.findById(sessionId).ifPresent(session -> {
            if (session.getApplianceType() == null) {
                session.setApplianceType(applianceType);
                log.info("call.session.appliance-classified sessionId={} applianceType={}",
                        sessionId, applianceType);
            }
        });
    }
}
