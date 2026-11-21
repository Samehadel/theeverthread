package com.theeverthread.identity.internal.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@Component
public class ResendClient {
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json;
    private final String baseUrl;
    private final String apiKey;

    public ResendClient(
            JsonMapper json,
            @Value("${theeverthread.email.api-url}") String baseUrl,
            @Value("${theeverthread.email.api-key}") String apiKey) {
        this.json = json;
        this.baseUrl = baseUrl.replaceFirst("/+$", "");
        this.apiKey = apiKey;
    }

    public sealed interface Submission permits Accepted, Rejected {}

    public record Accepted(UUID providerId) implements Submission {}

    public record Rejected(
            String code,
            String reason,
            boolean retryable,
            boolean uncertain,
            long retryAfterSeconds)
            implements Submission {}

    public Submission submit(UUID id, byte[] payload) {
        if (apiKey.isBlank()) {
            return new Rejected("RESEND_NOT_CONFIGURED", null, true, false, 0);
        }
        var request =
                request("/emails")
                        .header("Idempotency-Key", "theeverthread/" + id)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            JsonNode body = read(response.body());
            if (status >= 200 && status < 300) {
                UUID providerId = uuid(body.path("id").asString(""));
                return providerId == null
                        ? new Rejected("INVALID_ACCEPTANCE_RESPONSE", null, true, true, 0)
                        : new Accepted(providerId);
            }
            String providerCode = body.path("name").asString("");
            String code =
                    providerCode.matches("[a-zA-Z0-9_]{1,60}") ? providerCode : "HTTP_" + status;
            boolean concurrent = status == 409 && code.equals("concurrent_idempotent_requests");
            boolean uncertain = status >= 500 || status == 408 || concurrent;
            String reason = status == 401 || status == 403 ? null : value(body.path("message"));
            String retryAfter = response.headers().firstValue("Retry-After").orElse("");
            long retrySeconds = retryAfter.matches("[0-9]{1,9}") ? Long.parseLong(retryAfter) : 0;
            return new Rejected(code, reason, uncertain || status == 429, uncertain, retrySeconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Rejected("SUBMISSION_INTERRUPTED", null, true, true, 0);
        } catch (IOException exception) {
            return new Rejected("SUBMISSION_RESPONSE_UNAVAILABLE", null, true, true, 0);
        }
    }

    public ProviderEvent lookup(UUID providerId) {
        if (apiKey.isBlank()) {
            return null;
        }
        try {
            var response =
                    http.send(
                            request("/emails/" + providerId).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                var body = read(response.body());
                if (providerId.equals(uuid(body.path("id").asString("")))) {
                    return ProviderEvent.from(
                            "email." + body.path("last_event").asString(""), body);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            // Reconciliation is scheduled again until the observation window closes.
        }
        return null;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey);
    }

    private JsonNode read(byte[] bytes) {
        try {
            return json.readTree(bytes);
        } catch (tools.jackson.core.JacksonException exception) {
            return json.createObjectNode();
        }
    }

    public static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static String value(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }
}
