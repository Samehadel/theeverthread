package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.api.exception.EmailDeliveryException;

import jakarta.mail.MessagingException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class SmtpVerificationMail implements VerificationMail {
    private final JavaMailSender mail;
    private final String origin;
    private final String from;
    private final String host;
    private final String template;

    public SmtpVerificationMail(
            JavaMailSender mail,
            @Value("${theeverthread.registration.public-origin}") String origin,
            @Value("${theeverthread.registration.mail-from}") String from,
            @Value("${spring.mail.host:127.0.0.1}") String host)
            throws IOException {
        this.mail = mail;
        this.origin = origin;
        this.from = from;
        this.host = host;
        try (var stream = new ClassPathResource("mail/email-verification.html").getInputStream()) {
            this.template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String send(String email, Long id, String token) {
        if (from.endsWith("@theeverthread.local")
                && !java.util.Set.of("127.0.0.1", "localhost", "::1", "mailpit").contains(host)) {
            throw new EmailDeliveryException("SMTP_FROM_NOT_CONFIGURED");
        }
        var message = mail.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Verify your theeverthread email");
            // The fragment keeps the credential out of HTTP access logs and Referer headers.
            String link = origin + "/verify#" + id + "." + token;
            String html = template.replace("{{ link }}", HtmlUtils.htmlEscape(link));
            helper.setText(
                    "Complete your theeverthread registration:\n"
                            + link
                            + "\n\n"
                            + "This link expires in 24 hours. If you did not register, ignore this"
                            + " email.",
                    html);
            message.saveChanges();
            String messageId = message.getMessageID();
            mail.send(message);
            return messageId;
        } catch (MailAuthenticationException exception) {
            throw new EmailDeliveryException("SMTP_AUTHENTICATION_FAILED");
        } catch (MessagingException exception) {
            throw new EmailDeliveryException("SMTP_MESSAGE_INVALID");
        } catch (org.springframework.mail.MailException exception) {
            throw new EmailDeliveryException();
        }
    }
}
