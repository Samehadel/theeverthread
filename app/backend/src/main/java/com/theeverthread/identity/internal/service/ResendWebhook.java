package com.theeverthread.identity.internal.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
public class ResendWebhook {
    record Notification(
            String eventId,
            UUID providerId,
            Long outboxId,
            String outboxRef,
            Instant occurredAt,
            ProviderEvent event) {}

    private final SecretKeySpec secret;
    private final JsonMapper json;
    private final Clock clock;
    private final EmailDeliveryTransactions delivery;

    public ResendWebhook(
            @Value("${theeverthread.email.webhook-secret}") String signingSecret,
            JsonMapper json,
            Clock clock,
            EmailDeliveryTransactions delivery) {
        this.secret =
                signingSecret.isBlank()
                        ? null
                        : new SecretKeySpec(
                                Base64.getDecoder()
                                        .decode(signingSecret.replaceFirst("^whsec_", "")),
                                "HmacSHA256");
        this.json = json;
        this.clock = clock;
        this.delivery = delivery;
    }

    public boolean receive(String id, String timestamp, String signatures, byte[] body) {
        if (!verified(id, timestamp, signatures, body)) {
            return false;
        }
        try {
            var payload = json.readTree(body);
            var data = payload.path("data");
            var event = ProviderEvent.from(payload.path("type").asString(""), data);
            if (event == null) {
                return true;
            }
            var providerId = ResendClient.uuid(data.path("email_id").asString(""));
            var outboxId = data.path("tags").path("outbox_id").asLong(0);
            var outboxRef = data.path("tags").path("outbox_ref").asString("");
            var occurredAt = Instant.parse(payload.path("created_at").asString(""));
            if (providerId == null) {
                return false;
            }
            delivery.webhook(
                    new Notification(
                            id,
                            providerId,
                            outboxId > 0 ? outboxId : null,
                            outboxRef,
                            occurredAt,
                            event));
            return true;
        } catch (JacksonException | DateTimeParseException exception) {
            return false;
        }
    }

    private boolean verified(String id, String timestamp, String signatures, byte[] body) {
        if (secret == null
                || id == null
                || !id.matches("[a-zA-Z0-9_-]{1,160}")
                || timestamp == null
                || !timestamp.matches("[0-9]{1,12}")
                || signatures == null) {
            return false;
        }
        var at = Instant.ofEpochSecond(Long.parseLong(timestamp));
        if (Duration.between(at, clock.instant()).abs().compareTo(Duration.ofMinutes(5)) > 0) {
            return false;
        }
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(secret);
            hmac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            byte[] expected = hmac.doFinal(body);
            for (String signature : signatures.split(" ")) {
                if (signature.startsWith("v1,") && matches(expected, signature.substring(3))) {
                    return true;
                }
            }
            return false;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Webhook verification unavailable", exception);
        }
    }

    private boolean matches(byte[] expected, String signature) {
        try {
            return MessageDigest.isEqual(expected, Base64.getDecoder().decode(signature));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
