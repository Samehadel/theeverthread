package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.internal.entity.AccountStatus;
import com.theeverthread.identity.internal.entity.OutboundEmail;
import com.theeverthread.identity.internal.entity.OutboundEmail.Status;
import com.theeverthread.identity.internal.repository.*;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Claims and outcome changes commit separately from network I/O. */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailDeliveryTransactions {
    // Stay inside Resend's 24-hour idempotency window even when a prior response was lost.
    private static final Duration SUBMISSION_WINDOW = Duration.ofHours(23);
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(1);
    private static final Duration LOOKUP_WINDOW = Duration.ofDays(7);
    private static final PageRequest BATCH = PageRequest.of(0, 25);

    private final OutboundEmailRepository emails;
    private final VerificationEmailRepository verifications;
    private final InvitationRepository invitations;
    private final AccountRepository accounts;
    private final RegistrationLocks locks;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public record SubmissionClaim(Long id, int attempt, String payload, Instant validUntil) {}

    public record LookupClaim(Long id, UUID providerId) {}

    @Transactional(readOnly = true)
    public List<Long> dueSubmissions() {
        return emails.dueSubmissions(clock.instant(), BATCH);
    }

    @Transactional(readOnly = true)
    public List<Long> dueLookups() {
        return emails.dueLookups(clock.instant(), BATCH);
    }

    @Transactional
    public SubmissionClaim claim(Long id) {
        var email = emails.findForUpdate(id).orElse(null);
        var now = clock.instant();
        if (email == null
                || email.getStatus() != Status.PENDING
                || now.isBefore(email.getNextSubmissionAt())) {
            return null;
        }
        if (!eligible(email)) {
            finish(email, Status.CANCELLED, "LINK_NO_LONGER_NEEDED");
            return null;
        }
        if (email.isSubmissionInFlight()) {
            email.setSubmissionUncertain(true);
        }
        if (email.getFirstAttemptAt() == null) {
            email.setFirstAttemptAt(now);
        }
        if (!now.isBefore(email.getFirstAttemptAt().plus(SUBMISSION_WINDOW))) {
            finish(email, failureStatus(email), "RETRY_WINDOW_EXHAUSTED");
            return null;
        }
        Instant deadline = email.getFirstAttemptAt().plus(SUBMISSION_WINDOW);
        Instant leaseUntil = now.plus(CLAIM_LEASE);
        Instant validUntil = leaseUntil.isBefore(deadline) ? leaseUntil : deadline;
        email.setNextSubmissionAt(validUntil);
        email.setSubmissionInFlight(true);
        email.setAttempts(email.getAttempts() + 1);
        record(email, attemptKey(email) + ":start", "SUBMISSION_STARTED", now, null);
        return new SubmissionClaim(id, email.getAttempts(), email.getPayload(), validUntil);
    }

    @Transactional
    public void submitted(SubmissionClaim claim, ResendClient.Submission result) {
        var email = emails.findForUpdate(claim.id()).orElse(null);
        if (email == null
                || email.getAttempts() != claim.attempt()
                || email.getStatus() != Status.PENDING) {
            return;
        }
        email.setSubmissionInFlight(false);
        if (result instanceof ResendClient.Accepted(UUID providerId)) {
            markAccepted(email, providerId, clock.instant());
            record(
                    email,
                    attemptKey(email) + ":result",
                    "PROVIDER_ACCEPTED",
                    clock.instant(),
                    null);
        } else if (result instanceof ResendClient.Rejected rejection) {
            submissionFailed(email, rejection);
        }
    }

    private void submissionFailed(OutboundEmail email, ResendClient.Rejected rejection) {
        var now = clock.instant();
        email.setSubmissionUncertain(email.isSubmissionUncertain() || rejection.uncertain());
        record(email, attemptKey(email) + ":result", rejection.code(), now, rejection.reason());
        email.setFailureCode(rejection.code());
        if (!rejection.retryable()) {
            finish(email, failureStatus(email), rejection.code());
            return;
        }
        var deadline = email.getFirstAttemptAt().plus(SUBMISSION_WINDOW);
        long backoff = Math.min(900, 60L << Math.min(email.getAttempts() - 1, 4));
        long delay =
                Math.min(
                        SUBMISSION_WINDOW.toSeconds(),
                        Math.max(backoff, rejection.retryAfterSeconds()));
        Instant next = now.plusSeconds(delay);
        email.setNextSubmissionAt(next.isBefore(deadline) ? next : deadline);
    }

    @Transactional
    public LookupClaim claimLookup(Long id) {
        var email = emails.findForUpdate(id).orElse(null);
        var now = clock.instant();
        if (email == null
                || email.getStatus() != Status.PROVIDER_ACCEPTED
                || now.isBefore(email.getNextLookupAt())) {
            return null;
        }
        if (!now.isBefore(email.getAcceptedAt().plus(LOOKUP_WINDOW))) {
            finish(email, Status.UNKNOWN, "DELIVERY_OUTCOME_UNCONFIRMED");
            return null;
        }
        email.setNextLookupAt(now.plus(Duration.ofMinutes(30)));
        return new LookupClaim(id, email.getProviderId());
    }

    @Transactional
    void reconciled(LookupClaim claim, ProviderEvent event) {
        var email = lock(claim.id());
        if (event == null
                || email == null
                || !claim.providerId().equals(email.getProviderId())
                || email.getStatus() != Status.PROVIDER_ACCEPTED) {
            return;
        }
        var now = clock.instant();
        record(
                email,
                "lookup:" + email.getId() + ":" + event.kind(),
                event.kind(),
                now,
                event.reason());
        if (event.terminal()) {
            applyOutcome(email, event, now);
        }
    }

    @Transactional
    void webhook(ResendWebhook.Notification notification) {
        log.info("Webhook call received from {}", notification.providerId());
        var eventId = notification.eventId();
        var providerId = notification.providerId();
        var outboxId = notification.outboxId();
        var occurredAt = notification.occurredAt();
        var event = notification.event();
        Long id = emails.idForProvider(providerId).orElse(outboxId);
        if (id == null) {
            return;
        }
        var email = lock(id);
        if (email == null
                || email.getFirstAttemptAt() == null
                || (email.getProviderId() != null && !providerId.equals(email.getProviderId()))) {
            return;
        }
        if (email.getProviderId() == null
                && (email.getTokenHash() == null
                        || !IdentityServiceImpl.hash(email.getTokenHash())
                                .equals(notification.outboxRef()))) {
            return;
        }
        if (!record(email, "webhook:" + eventId, event.kind(), occurredAt, event.reason())) {
            return;
        }
        if (email.getProviderId() == null) {
            markAccepted(email, providerId, occurredAt);
        }
        if (event.terminal() && isNewOutcome(email, event, occurredAt)) {
            applyOutcome(email, event, occurredAt);
        }
    }

    private boolean isNewOutcome(OutboundEmail email, ProviderEvent event, Instant at) {
        return email.getOutcomeAt() == null
                || at.isAfter(email.getOutcomeAt())
                || (at.equals(email.getOutcomeAt())
                        && event.status() == Status.FAILED
                        && email.getStatus() == Status.DELIVERED);
    }

    private void markAccepted(OutboundEmail email, UUID providerId, Instant acceptedAt) {
        email.setProviderId(providerId);
        email.setAcceptedAt(acceptedAt);
        email.setNextLookupAt(acceptedAt.plus(Duration.ofMinutes(5)));
        email.setStatus(Status.PROVIDER_ACCEPTED);
        email.setFailureCode(null);
        stopSubmitting(email);
    }

    private void applyOutcome(OutboundEmail email, ProviderEvent event, Instant at) {
        Status previous = email.getStatus();
        email.setOutcomeAt(at);
        email.setStatus(event.status());
        email.setFailureCode(event.status() == Status.FAILED ? event.kind() : null);
        email.setNextLookupAt(null);
        if (previous != event.status() && eligible(email)) {
            updateLink(email, event.status() == Status.DELIVERED, at);
        }
    }

    private void updateLink(OutboundEmail email, boolean delivered, Instant at) {
        if (email.getAccountId() != null) {
            var verification = verifications.findById(email.getAccountId()).orElseThrow();
            if (delivered) {
                rememberLink(
                        email,
                        verification.getTokenHash(),
                        verification.getTokenExpiresAt(),
                        verification.getLastSentAt());
                verification.setTokenHash(email.getTokenHash());
                verification.setLastSentAt(at);
                verification.setTokenExpiresAt(at.plus(email.linkLifetime()));
            } else if (email.getTokenHash().equals(verification.getTokenHash())) {
                verification.setTokenHash(email.getPreviousTokenHash());
                verification.setTokenExpiresAt(email.getPreviousExpiresAt());
                verification.setLastSentAt(email.getPreviousSentAt());
            }
            return;
        }

        var invitation = invitations.findById(email.getInvitationId()).orElseThrow();
        if (delivered) {
            rememberLink(
                    email,
                    invitation.getTokenHash(),
                    invitation.getExpiresAt(),
                    invitation.getSentAt());
            invitation.setTokenHash(email.getTokenHash());
            invitation.setExpiresAt(at.plus(email.linkLifetime()));
            invitation.setSentAt(at);
            invitation.setStatus("READY");
        } else if (email.getTokenHash().equals(invitation.getTokenHash())) {
            invitation.setTokenHash(email.getPreviousTokenHash());
            invitation.setExpiresAt(email.getPreviousExpiresAt());
            invitation.setSentAt(email.getPreviousSentAt());
            invitation.setStatus(email.getPreviousTokenHash() == null ? "FAILED" : "READY");
        }
    }

    private void rememberLink(OutboundEmail email, String hash, Instant expiry, Instant sentAt) {
        email.setPreviousTokenHash(hash);
        email.setPreviousExpiresAt(expiry);
        email.setPreviousSentAt(sentAt);
    }

    private boolean eligible(OutboundEmail email) {
        if (email.getAccountId() != null) {
            return accounts.findById(email.getAccountId())
                    .filter(account -> account.getStatus() == AccountStatus.PENDING)
                    .flatMap(account -> verifications.findById(account.getId()))
                    .filter(verification -> email.getId().equals(verification.getLatestEmailId()))
                    .isPresent();
        }
        return email.getInvitationId() != null
                && invitations
                        .findById(email.getInvitationId())
                        .filter(invitation -> email.getId().equals(invitation.getLatestEmailId()))
                        .filter(
                                invitation ->
                                        !"ACCEPTED".equals(invitation.getStatus())
                                                && !"REVOKED".equals(invitation.getStatus()))
                        .isPresent();
    }

    // Link updates share the signup and acceptance lock, including when no account row exists.
    private OutboundEmail lock(Long id) {
        var recipient = emails.recipient(id);
        if (recipient.isEmpty()) {
            return null;
        }
        locks.lock("email:" + recipient.get());
        return emails.findForUpdate(id).orElse(null);
    }

    private void finish(OutboundEmail email, Status status, String code) {
        email.setStatus(status);
        email.setFailureCode(code);
        email.setNextLookupAt(null);
        stopSubmitting(email);
        record(email, "finish:" + email.getId() + ":" + status, code, clock.instant(), null);
    }

    private void stopSubmitting(OutboundEmail email) {
        email.setNextSubmissionAt(null);
        email.setSubmissionInFlight(false);
        email.setPayload(null);
    }

    private Status failureStatus(OutboundEmail email) {
        return email.isSubmissionUncertain() ? Status.UNKNOWN : Status.FAILED;
    }

    private String attemptKey(OutboundEmail email) {
        return "attempt:" + email.getId() + ":" + email.getAttempts();
    }

    private boolean record(
            OutboundEmail email, String key, String kind, Instant occurredAt, String reason) {
        String safeReason = reason == null ? null : reason.replaceAll("[\\p{Cntrl}]", " ");
        if (safeReason != null && safeReason.length() > 1000) {
            safeReason = safeReason.substring(0, 1000);
        }
        return jdbc.update(
                        """
                        INSERT INTO EMAIL_DELIVERY_EVENT
                            (EVENT_KEY, EMAIL_ID, KIND, OCCURRED_AT, RECORDED_AT, REASON)
                        VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (EVENT_KEY) DO NOTHING
                        """,
                        key,
                        email.getId(),
                        kind,
                        Timestamp.from(occurredAt),
                        Timestamp.from(clock.instant()),
                        safeReason)
                == 1;
    }
}
