package com.theeverthread.identity.internal.service;

import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.UUID;

interface DeliveryProvider {
    byte[] prepare(
            UUID key, String sender, String recipient, String subject, String text, String html);

    String submit(UUID key, String payload);

    String lookup(String providerId);

    Notice verify(byte[] body, HttpHeaders headers);

    /** Safe reason and retry instructions for one submission. No response body or credentials. */
    final class SubmissionFailure extends RuntimeException {
        final boolean retryable;
        final boolean throttle;
        final int waitSeconds;

        SubmissionFailure(String code, boolean retryable, boolean throttle, int waitSeconds) {
            super(code);
            this.retryable = retryable;
            this.throttle = throttle;
            this.waitSeconds = waitSeconds;
        }
    }

    record Notice(
            String eventKey,
            String providerId,
            UUID key,
            String outcome,
            Instant at,
            String reason) {}
}
