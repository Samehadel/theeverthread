package com.theeverthread.identity.internal.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Component
@ConditionalOnProperty(name = "email.provider", havingValue = "resend")
class QueuedEmail {
    private final JdbcTemplate db;
    private final DeliveryProvider provider;
    private final String template;
    private final SecretKeySpec secret;
    private final String from;
    private final String origin;
    private final SecureRandom random = new SecureRandom();

    QueuedEmail(
            JdbcTemplate db,
            DeliveryProvider provider,
            @Value("${EMAIL_PAYLOAD_KEY}") String key,
            @Value("${theeverthread.registration.mail-from}") String from,
            @Value("${theeverthread.registration.public-origin}") String origin)
            throws java.io.IOException {
        this.db = db;
        this.provider = provider;
        template =
                new org.springframework.core.io.ClassPathResource("mail/team-invitation.html")
                        .getContentAsString(StandardCharsets.UTF_8);
        byte[] bytes = Base64.getDecoder().decode(key);
        if (bytes.length != 32)
            throw new IllegalArgumentException("EMAIL_PAYLOAD_KEY requires 32 bytes");
        secret = new SecretKeySpec(bytes, "AES");
        this.from = from;
        this.origin = origin;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void verification(long id, String email, Instant expiry) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        enqueue(
                id,
                null,
                token,
                expiry,
                email,
                "Verify your theeverthread email",
                "Complete your registration: " + origin + "/verify#" + id + "." + token,
                null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void invitation(InvitationTransactions.Claim claim, String token) {
        var view = claim.view();
        String link = origin + "/invitation/accept#" + view.id() + "." + token;
        String html =
                SmtpInvitationMail.render(
                        template,
                        link,
                        view.expiresAt().toString(),
                        claim.workspaceName(),
                        claim.administratorName());
        enqueue(
                null,
                view.id(),
                token,
                view.expiresAt(),
                view.email(),
                "You are invited to join your team on theeverthread",
                claim.administratorName()
                        + " invited you to "
                        + claim.workspaceName()
                        + ". Accept your invitation: "
                        + origin
                        + "/invitation/accept#"
                        + view.id()
                        + "."
                        + token
                        + "\nExpires: "
                        + view.expiresAt(),
                html);
    }

    private void enqueue(
            Long verification,
            Long invitation,
            String token,
            Instant expiry,
            String email,
            String subject,
            String text,
            String html) {
        UUID key = UUID.randomUUID();
        var body = provider.prepare(key, from, email, subject, text, html);
        db.update(
                "INSERT INTO"
                    + " email_outbox(message_key,verification_id,invitation_id,token_hash,payload,expires_at)"
                    + " VALUES(?,?,?,?,?,?)",
                key,
                verification,
                invitation,
                IdentityServiceImpl.hash(token),
                encrypt(key, body),
                java.sql.Timestamp.from(expiry));
    }

    private byte[] encrypt(UUID key, byte[] body) {
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, key, iv, body);
        return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
    }

    String decrypt(UUID key, byte[] data) {
        return new String(
                crypt(
                        Cipher.DECRYPT_MODE,
                        key,
                        Arrays.copyOf(data, 12),
                        Arrays.copyOfRange(data, 12, data.length)),
                StandardCharsets.UTF_8);
    }

    private byte[] crypt(int mode, UUID key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, secret, new GCMParameterSpec(128, iv));
            cipher.updateAAD(key.toString().getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to process encrypted email");
        }
    }
}
