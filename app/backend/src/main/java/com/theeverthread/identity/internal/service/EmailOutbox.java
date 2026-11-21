package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.api.exception.IdentityException;
import com.theeverthread.identity.internal.entity.OutboundEmail;
import com.theeverthread.identity.internal.repository.OutboundEmailRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Stores the exact Resend request encrypted while it can still be submitted. */
@Component
public class EmailOutbox {
    private final OutboundEmailRepository emails;
    private final JsonMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;
    private final String origin;
    private final String from;
    private final String invitationTemplate;

    public EmailOutbox(
            OutboundEmailRepository emails,
            JsonMapper json,
            Clock clock,
            @Value("${theeverthread.registration.public-origin}") String origin,
            @Value("${theeverthread.email.from}") String from,
            @Value("${theeverthread.email.payload-key}") String encodedKey)
            throws IOException {
        this.emails = emails;
        this.json = json;
        this.clock = clock;
        this.origin = origin;
        this.from = from;
        byte[] keyBytes = Base64.getDecoder().decode(encodedKey);
        if (keyBytes.length != 0 && keyBytes.length != 32) {
            throw new IllegalArgumentException("EMAIL_PAYLOAD_KEY must encode 32 bytes");
        }
        this.key = keyBytes.length == 0 ? null : new SecretKeySpec(keyBytes, "AES");
        try (var stream = new ClassPathResource("mail/team-invitation.html").getInputStream()) {
            this.invitationTemplate = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID verification(Long accountId, String recipient) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        String link = origin + "/verify#" + accountId + "." + token;
        return enqueue(
                accountId,
                null,
                null,
                recipient,
                token,
                "Verify your theeverthread email",
                "Complete your theeverthread registration:\n"
                        + link
                        + "\n\n"
                        + "This link expires in 24 hours. If you did not register, ignore this"
                        + " email.",
                null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID invitation(
            Long invitationId, UUID seriesId, String recipient, String workspace, String inviter) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        String link = origin + "/invitation/accept#" + invitationId + "." + token;
        String html = invitationTemplate;
        html = replace(html, "link", link);
        html = replace(html, "workspace", workspace);
        html = replace(html, "administrator", inviter);
        return enqueue(
                null,
                invitationId,
                seriesId,
                recipient,
                token,
                "You are invited to join your team on theeverthread",
                inviter
                        + " invited you to "
                        + workspace
                        + ". Accept invitation: "
                        + link
                        + "\nThis invitation expires in seven days.",
                html);
    }

    private UUID enqueue(
            Long accountId,
            Long invitationId,
            UUID seriesId,
            String recipient,
            String token,
            String subject,
            String text,
            String html) {
        if (key == null) {
            throw new IdentityException(503, "EMAIL_UNAVAILABLE", "Email delivery is unavailable.");
        }
        var email = new OutboundEmail();
        email.setId(UUID.randomUUID());
        email.setAccountId(accountId);
        email.setInvitationId(invitationId);
        if (invitationId != null) {
            email.setSeriesId(seriesId == null ? email.getId() : seriesId);
        }
        email.setRecipient(recipient);
        email.setStatus(OutboundEmail.Status.PENDING);
        email.setCreatedAt(clock.instant());
        email.setNextSubmissionAt(email.getCreatedAt());
        email.setTokenHash(IdentityServiceImpl.hash(token));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("from", from);
        request.put("to", List.of(recipient));
        request.put("subject", subject);
        request.put("text", text);
        if (html != null) {
            request.put("html", html);
        }
        request.put(
                "tags", List.of(Map.of("name", "outbox_id", "value", email.getId().toString())));
        email.setPayload(encrypt(email.getId(), json.writeValueAsBytes(request)));
        emails.save(email);
        return email.getId();
    }

    byte[] decrypt(UUID id, String payload) {
        var buffer = ByteBuffer.wrap(Base64.getDecoder().decode(payload));
        byte[] nonce = new byte[12];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        return crypt(Cipher.DECRYPT_MODE, id, nonce, ciphertext);
    }

    private String encrypt(UUID id, byte[] payload) {
        byte[] nonce = randomBytes(12);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, id, nonce, payload);
        return Base64.getEncoder()
                .encodeToString(
                        ByteBuffer.allocate(nonce.length + ciphertext.length)
                                .put(nonce)
                                .put(ciphertext)
                                .array());
    }

    private byte[] crypt(int mode, UUID id, byte[] nonce, byte[] body) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(id.toString().getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(body);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Outbox payload could not be processed", exception);
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String replace(String html, String name, String value) {
        return html.replaceAll(
                "\\{\\{\\s*" + name + "\\s*}}",
                java.util.regex.Matcher.quoteReplacement(HtmlUtils.htmlEscape(value)));
    }
}
