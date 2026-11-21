package com.theeverthread.identity.internal.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** The account’s current verification link and latest email reference. */
@Entity
@Table(name = "VERIFICATION_EMAIL")
@Getter
@Setter
public class VerificationEmailEntity {
    @Id
    private Long accountId;

    @Column(nullable = false, length = 254)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmailDeliveryStatus status;

    // Legacy scheduling fields are read during the SMTP cutover.
    private Instant nextAttemptAt;

    private Long latestEmailId;

    private Instant lastSentAt;

    @Column(length = 64)
    private String tokenHash;

    private Instant tokenExpiresAt;
}
