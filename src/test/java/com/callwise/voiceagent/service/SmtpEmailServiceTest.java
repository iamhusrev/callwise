package com.callwise.voiceagent.service;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real {@link JavaMailSenderImpl} against an in-process GreenMail SMTP server. We
 * assert the rendered body contains the link and the recipient — confirms the Thymeleaf
 * template wiring works end-to-end without spinning up Spring.
 */
class SmtpEmailServiceTest {

    @RegisterExtension
    static final GreenMailExtension GREEN = new GreenMailExtension(ServerSetupTest.SMTP);

    private SmtpEmailService newService() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(GREEN.getSmtp().getPort());

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        return new SmtpEmailService(sender, engine, "no-reply@callwise.test");
    }

    @Test
    void sendUploadLink_deliversHtmlWithLinkAndRecipient() throws Exception {
        SmtpEmailService service = newService();

        service.sendUploadLink("alice@example.com",
                "https://callwise.test/uploads/abc123",
                "to see your fridge leak",
                30);

        MimeMessage[] received = GREEN.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage msg = received[0];
        assertThat(msg.getAllRecipients()[0].toString()).isEqualTo("alice@example.com");
        assertThat(msg.getSubject()).contains("upload");

        String body = GreenMailUtil.getBody(msg);
        assertThat(body).contains("https://callwise.test/uploads/abc123");
        assertThat(body).contains("30");
        assertThat(body).contains("to see your fridge leak");
    }
}
