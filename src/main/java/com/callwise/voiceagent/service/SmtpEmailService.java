package com.callwise.voiceagent.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Tier 3 — JavaMail-based SMTP sender. Talks to MailHog in dev, can talk to any SMTP server
 * in production by changing {@code spring.mail.*}. The HTML body is rendered with Thymeleaf;
 * a plain-text fallback is set so non-HTML mail clients still see the link.
 *
 * <p>Privacy: full recipient address is logged at DEBUG only. INFO logs surface a SHA-256
 * truncated fingerprint instead, matching CLAUDE.md's PII-logging rule.
 */
@Service
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;
    private final String fromAddress;

    public SmtpEmailService(
            JavaMailSender mailSender,
            SpringTemplateEngine templateEngine,
            @Value("${callwise.uploads.mail-from:no-reply@callwise.local}") String fromAddress
    ) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendUploadLink(String to, String link, String reason, int ttlMinutes) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject("Sears Home Services — please upload a photo of your appliance");

            Context ctx = new Context();
            ctx.setVariable("link", link);
            ctx.setVariable("reason", reason == null || reason.isBlank() ? "to help diagnose your appliance issue" : reason);
            ctx.setVariable("ttlMinutes", ttlMinutes);

            String html = templateEngine.process("emails/upload-link", ctx);
            String text = "Hello,\n\nPlease upload a photo of your appliance using this link:\n\n"
                    + link + "\n\nThe link expires in " + ttlMinutes + " minutes.\n";
            helper.setText(text, html);

            mailSender.send(mime);
            log.info("email.sent recipient={} ttlMinutes={}", fingerprint(to), ttlMinutes);
        } catch (MessagingException e) {
            log.error("email.failed recipient={} err={}", fingerprint(to), e.getMessage());
            throw new RuntimeException("Failed to send upload link email", e);
        }
    }

    private static String fingerprint(String value) {
        if (value == null) return "null";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}
