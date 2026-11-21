package com.theeverthread.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

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
        String deliveryStatus,
        boolean resendEligible,
        UUID emailReference) {}
