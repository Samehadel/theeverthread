package com.theeverthread.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record InvitationView(
        @JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
        String email,
        String status,
        Instant expiresAt,
        String message,
        Instant sentAt,
        Instant resendAvailableAt,
        boolean resending,
        boolean deliveryFailed,
        String deliveryStatus) {
    public InvitationView(
            Long id,
            String email,
            String status,
            Instant expiresAt,
            String message,
            Instant sentAt,
            Instant resendAvailableAt,
            boolean resending,
            boolean deliveryFailed) {
        this(
                id,
                email,
                status,
                expiresAt,
                message,
                sentAt,
                resendAvailableAt,
                resending,
                deliveryFailed,
                null);
    }

    public InvitationView(Long id, String email, String status, Instant expiresAt, String message) {
        this(id, email, status, expiresAt, message, null, null, false, false);
    }
}
