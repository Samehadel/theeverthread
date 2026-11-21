package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.internal.entity.OutboundEmail.Status;

import tools.jackson.databind.JsonNode;

record ProviderEvent(String kind, Status status, String reason) {
    static ProviderEvent from(String kind, JsonNode data) {
        return switch (kind) {
            case "email.sent", "email.delivery_delayed" ->
                    new ProviderEvent(kind, Status.PROVIDER_ACCEPTED, null);
            case "email.delivered" -> new ProviderEvent(kind, Status.DELIVERED, null);
            case "email.failed" ->
                    new ProviderEvent(
                            kind,
                            Status.FAILED,
                            ResendClient.value(data.path("failed").path("reason")));
            case "email.bounced" ->
                    new ProviderEvent(kind, Status.FAILED, diagnostic(data.path("bounce")));
            case "email.suppressed" ->
                    new ProviderEvent(kind, Status.FAILED, diagnostic(data.path("suppressed")));
            default -> null;
        };
    }

    private static String diagnostic(JsonNode details) {
        String message = ResendClient.value(details.path("message"));
        return message == null ? ResendClient.value(details.path("type")) : message;
    }

    boolean terminal() {
        return status == Status.DELIVERED || status == Status.FAILED;
    }
}
