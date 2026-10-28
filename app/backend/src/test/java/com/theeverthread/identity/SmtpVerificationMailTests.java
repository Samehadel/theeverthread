package com.theeverthread.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.theeverthread.identity.api.exception.EmailDeliveryException;
import com.theeverthread.identity.internal.service.SmtpVerificationMail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

class SmtpVerificationMailTests {
    @Test
    void sendsVerificationCredentialOnlyInLinkFragment() throws Exception {
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var mail =
                new SmtpVerificationMail(
                        sender,
                        "https://theeverthread.example",
                        "no-reply@theeverthread.example",
                        "127.0.0.1");
        var id = 123L;
        mail.send("ada@example.com", id, "test-token");
        var message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getAllRecipients()[0].toString())
                .isEqualTo("ada@example.com");
        var parts = new java.util.ArrayList<String>();
        collect(message.getValue().getContent(), parts);
        assertThat(parts).hasSize(2);
        assertThat(parts.get(1))
                .contains("Verify email", "background: #6850a3", "name=\"viewport\"")
                .contains("href=\"https://theeverthread.example/verify#123.test-token\"")
                .contains("24 hours", "If you did not register")
                .doesNotContain("{{");
        assertThat(parts.get(0))
                .contains("https://theeverthread.example/verify#" + id + ".test-token")
                .contains("24 hours");
    }

    @Test
    void deliveryFailureIsSafeAndTypedForPersistingFailureState() throws Exception {
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("private SMTP diagnostic"))
                .when(sender)
                .send(any(MimeMessage.class));
        var mail =
                new SmtpVerificationMail(
                        sender,
                        "https://theeverthread.example",
                        "no-reply@theeverthread.example",
                        "127.0.0.1");
        assertThatThrownBy(() -> mail.send("ada@example.com", 123L, "test-token"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageNotContaining("private SMTP diagnostic");
    }

    @Test
    void brevoRequiresAConfiguredSenderInsteadOfTheLocalDefault() throws Exception {
        var sender = mock(JavaMailSender.class);
        var mail =
                new SmtpVerificationMail(
                        sender,
                        "http://localhost:4200",
                        "no-reply@theeverthread.local",
                        "smtp-relay.brevo.com");
        assertThatThrownBy(() -> mail.send("ada@example.com", 123L, "token"))
                .isInstanceOfSatisfying(
                        EmailDeliveryException.class,
                        exception ->
                                assertThat(exception.failureCode())
                                        .isEqualTo("SMTP_FROM_NOT_CONFIGURED"));
        verifyNoInteractions(sender);
    }

    private static void collect(Object content, java.util.List<String> parts) throws Exception {
        if (content instanceof jakarta.mail.Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i).getContent(), parts);
            }
        } else if (content instanceof String text) {
            parts.add(text);
        }
    }
}
