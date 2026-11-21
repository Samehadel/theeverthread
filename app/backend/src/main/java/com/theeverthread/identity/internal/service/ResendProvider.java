package com.theeverthread.identity.internal.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
@ConditionalOnProperty(name = "email.provider", havingValue = "resend")
class ResendProvider implements DeliveryProvider {
    private final RestClient client;
    private final byte[] secret;
    private final JsonMapper json;
    private final Clock clock;

    ResendProvider(
            @Value("${RESEND_API_KEY}") String key,
            @Value("${RESEND_WEBHOOK_SECRET}") String signingKey,
            JsonMapper json,
            Clock clock) {
        if (key.isBlank() || !signingKey.startsWith("whsec_"))
            throw new IllegalArgumentException("Configure Resend credentials");
        secret = Base64.getDecoder().decode(signingKey.substring(6));
        if (secret.length < 16) throw new IllegalArgumentException("Invalid webhook signing key");
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(8000);
        client =
                RestClient.builder()
                        .requestFactory(factory)
                        .baseUrl("https://api.resend.com")
                        .defaultHeader("Authorization", "Bearer " + key)
                        .build();
        this.json = json;
        this.clock = clock;
    }

    public byte[] prepare(
            UUID key, String sender, String recipient, String subject, String text, String html) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("from", sender);
        fields.put("to", List.of(recipient));
        fields.put("subject", subject);
        fields.put("text", text);
        if (html != null) fields.put("html", html);
        fields.put("tags", List.of(Map.of("name", "message_key", "value", key.toString())));
        return json.writeValueAsBytes(fields);
    }

    public String submit(UUID key, String body) {
        try {
            var response =
                    client.post()
                            .uri("/emails")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", key.toString())
                            .body(body)
                            .retrieve()
                            .body(String.class);
            return UUID.fromString(json.readTree(response).path("id").asString()).toString();
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw failure(e);
        }
    }

    private SubmissionFailure failure(
            org.springframework.web.client.RestClientResponseException error) {
        int status = error.getStatusCode().value();
        String name = "";
        try {
            name = json.readTree(error.getResponseBodyAsString()).path("name").asString("");
        } catch (tools.jackson.core.JacksonException ignored) {
            /* Some gateways return HTML. */
        }
        boolean quota =
                name.equals("daily_quota_exceeded") || name.equals("monthly_quota_exceeded");
        boolean retryable =
                status >= 500
                        || status == 408
                        || (status == 429 && !quota)
                        || name.equals("concurrent_idempotent_requests");
        int wait = 0;
        if (error.getResponseHeaders() != null) {
            String retry = error.getResponseHeaders().getFirst("Retry-After");
            if (retry != null && retry.matches("[0-9]{1,8}")) wait = Integer.parseInt(retry);
        }
        return new SubmissionFailure(
                name.matches("[a-z_]{1,64}") ? name : "HTTP_" + status,
                retryable,
                status == 429,
                wait);
    }

    public String lookup(String id) {
        var response = client.get().uri("/emails/{id}", id).retrieve().body(String.class);
        return json.readTree(response).path("last_event").asString();
    }

    public Notice verify(byte[] body, HttpHeaders headers) {
        String id = headers.getFirst("svix-id");
        String timestamp = headers.getFirst("svix-timestamp");
        String signatures = headers.getFirst("svix-signature");
        if (id == null
                || timestamp == null
                || signatures == null
                || !valid(body, id, timestamp, signatures))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var event = json.readTree(body);
        var data = event.path("data");
        String type = event.path("type").asString().replaceFirst("^email\\.", "");
        String tag = data.path("tags").path("message_key").asString("");
        var detail =
                switch (type) {
                    case "failed" -> data.path("failed").path("reason");
                    case "bounced" -> data.path("bounce").path("message");
                    case "suppressed" -> data.path("suppressed").path("message");
                    default -> data.path("reason");
                };
        return new Notice(
                id,
                data.path("email_id").asString(),
                tag.isEmpty() ? null : UUID.fromString(tag),
                type,
                Instant.parse(event.path("created_at").asString()),
                detail.asString(""));
    }

    private boolean valid(byte[] body, String id, String timestamp, String signatures) {
        try {
            Instant at = Instant.ofEpochSecond(Long.parseLong(timestamp));
            if (Duration.between(at, clock.instant()).abs().compareTo(Duration.ofMinutes(5)) > 0)
                return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            byte[] expected = mac.doFinal(body);
            for (String candidate : signatures.split(" ")) {
                if (candidate.startsWith("v1,")
                        && MessageDigest.isEqual(
                                expected, Base64.getDecoder().decode(candidate.substring(3))))
                    return true;
            }
            return false;
        } catch (RuntimeException | java.security.GeneralSecurityException invalid) {
            return false;
        }
    }
}
