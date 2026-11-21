package com.theeverthread.identity.internal.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "EMAIL_OUTBOX")
@Getter
@Setter
public class OutboundEmail {
    public enum Status {
        PENDING,
        PROVIDER_ACCEPTED,
        DELIVERED,
        FAILED,
        UNKNOWN,
        CANCELLED
    }

    @Id private UUID id;

    private Long accountId;
    private Long invitationId;
    private UUID seriesId;

    @Column(nullable = false, length = 254)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    private Instant createdAt;
    private String payload;
    private String tokenHash;
    private int attempts;
    private Instant firstAttemptAt;
    private Instant nextSubmissionAt;
    private boolean submissionInFlight;
    private boolean submissionUncertain;
    private UUID providerId;
    private Instant acceptedAt;
    private Instant nextLookupAt;
    private Instant outcomeAt;
    private String failureCode;
    private String previousTokenHash;
    private Instant previousExpiresAt;
    private Instant previousSentAt;

    public Duration linkLifetime() {
        return accountId != null ? Duration.ofHours(24) : Duration.ofDays(7);
    }

    /** Provisional links stay usable until a later confirmed link supersedes them. */
    public Instant provisionalExpiry(Instant confirmedAt) {
        if (firstAttemptAt == null
                || status == Status.FAILED
                || status == Status.CANCELLED
                || (confirmedAt != null && !createdAt.isAfter(confirmedAt))) {
            return null;
        }
        return (acceptedAt == null ? firstAttemptAt : acceptedAt).plus(linkLifetime());
    }
}
