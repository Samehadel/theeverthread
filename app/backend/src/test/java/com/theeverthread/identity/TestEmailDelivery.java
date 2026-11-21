package com.theeverthread.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.theeverthread.identity.internal.service.ResendClient;
import com.theeverthread.identity.internal.service.ResendWebhook;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class TestEmailDelivery {
    private static final JsonMapper JSON = new JsonMapper();
    private static final Pattern LINK = Pattern.compile("#([0-9]+)\\.([A-Za-z0-9_-]{43})");
    private final ResendWebhook webhook;
    private final Clock clock;
    private final CopyOnWriteArrayList<Message> messages = new CopyOnWriteArrayList<>();

    record Message(UUID outboxId, UUID providerId, String recipient, Long targetId, String token) {}

    TestEmailDelivery(ResendClient resend, ResendWebhook webhook, Clock clock) {
        this.webhook = webhook;
        this.clock = clock;
        doAnswer(
                        call -> {
                            var message = parse(call.getArgument(0), call.getArgument(1));
                            messages.add(message);
                            event(message, "email.delivered", clock.instant(), Map.of());
                            return new ResendClient.Accepted(message.providerId());
                        })
                .when(resend)
                .submit(any(), any(byte[].class));
    }

    static Message parse(UUID outboxId, byte[] payload) {
        var json = JSON.readTree(payload);
        var match = LINK.matcher(json.path("text").asString());
        assertThat(match.find()).isTrue();
        return new Message(
                outboxId,
                UUID.randomUUID(),
                json.path("to").get(0).asString(),
                Long.valueOf(match.group(1)),
                match.group(2));
    }

    Message latest(String recipient) {
        return messages.reversed().stream()
                .filter(message -> message.recipient().equals(recipient))
                .findFirst()
                .orElseThrow();
    }

    Message message(UUID outboxId) {
        return messages.stream()
                .filter(message -> message.outboxId().equals(outboxId))
                .findFirst()
                .orElseThrow();
    }

    void event(Message message, String type, Instant when, Map<String, Object> details) {
        var payload = payload(message, type, when, details);
        var id = "msg_" + UUID.randomUUID();
        var timestamp = Long.toString(clock.instant().getEpochSecond());
        assertThat(webhook.receive(id, timestamp, signature(id, timestamp, payload), payload))
                .isTrue();
    }

    static byte[] payload(Message message, String type, Instant when, Map<String, Object> details) {
        var data = new java.util.HashMap<>(details);
        data.put("email_id", message.providerId().toString());
        data.put("tags", Map.of("outbox_id", message.outboxId().toString()));
        return JSON.writeValueAsBytes(
                Map.of("type", type, "created_at", when.toString(), "data", data));
    }

    static String signature(String id, String timestamp, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException exception) {
            throw new AssertionError(exception);
        }
    }

    static ResendClient.Rejected rejected() {
        return new ResendClient.Rejected("validation_error", "Recipient rejected", false, false, 0);
    }

    static ResendClient.Rejected temporary() {
        return new ResendClient.Rejected("rate_limit_exceeded", null, true, false, 0);
    }
}
