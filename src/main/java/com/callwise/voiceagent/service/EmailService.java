package com.callwise.voiceagent.service;

/**
 * Tier 3 — send an upload-link email to the caller. Implementation lives in
 * {@link SmtpEmailService} and talks to MailHog (offline) over plain SMTP.
 */
public interface EmailService {

    /**
     * Send a short HTML+text email containing the upload link.
     *
     * @param to       recipient address (validated upstream)
     * @param link     fully-qualified URL the caller clicks to upload a photo
     * @param reason   short human-readable reason from the AI ("for your refrigerator leak")
     * @param ttlMinutes how long the link stays valid — surfaced in the email body
     */
    void sendUploadLink(String to, String link, String reason, int ttlMinutes);
}
