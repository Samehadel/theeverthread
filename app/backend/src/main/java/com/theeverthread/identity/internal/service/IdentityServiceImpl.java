package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.api.dto.*;
import com.theeverthread.identity.api.dto.IdentityViews.*;
import com.theeverthread.identity.api.exception.IdentityException;
import com.theeverthread.identity.api.service.IdentityService;
import com.theeverthread.identity.internal.entity.*;
import com.theeverthread.identity.internal.repository.*;

import jakarta.validation.Validator;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
@lombok.RequiredArgsConstructor
public class IdentityServiceImpl implements IdentityService {
    private final AccountRepository accounts;
    private final VerificationEmailRepository emails;
    private final RegistrationLocks locks;
    private final OutboundEmailRepository outboundEmails;
    private final EmailOutbox outbox;
    private final PasswordEncoder passwords;
    private final WorkspaceAccess workspaces;
    private final Clock clock;
    private final Validator validator;

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static IdentityException invalidLink() {
        return new IdentityException(
                400,
                "INVALID_VERIFICATION",
                "This link is invalid or has been replaced. Request a new verification email.");
    }

    @Override
    @Transactional
    public PendingView register(RegistrationRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new IdentityException(
                    400,
                    "INVALID_REQUEST",
                    "Enter your name, a valid email and a password of 10–128 characters with an"
                            + " uppercase letter and a special character.");
        }
        String email = normalizeEmail(request.email());
        locks.lock("email:" + email);
        var existing = accounts.findByEmail(email);
        if (existing.isPresent()) {
            throw emailInUse();
        }
        var account = new AccountEntity();
        account.setEmail(email);
        account.setFullName(request.fullName().trim());
        account.setPasswordHash(passwords.encode(request.password()));
        account.setStatus(AccountStatus.PENDING);
        account.setUpdatedAt(clock.instant());
        accounts.save(account);
        var delivery = new VerificationEmailEntity();
        delivery.setAccountId(account.getId());
        delivery.setRecipient(email);
        delivery.setStatus(EmailDeliveryStatus.PENDING);
        delivery.setLatestEmailId(outbox.verification(account.getId(), email));
        emails.save(delivery);
        return pending(account);
    }

    static IdentityException emailInUse() {
        return new IdentityException(
                409,
                "EMAIL_IN_USE",
                "This email is already registered. Log in with your existing method, or request"
                        + " another verification email if registration is pending.");
    }

    @Override
    @Transactional
    public PendingView resend(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        locks.lock("email:" + email);
        var account =
                accounts.findByEmail(email)
                        .orElseThrow(
                                () ->
                                        new IdentityException(
                                                400,
                                                "REGISTRATION_NOT_FOUND",
                                                "No pending registration was found. Sign up or log"
                                                        + " in if already verified."));
        if (account.getStatus() == AccountStatus.ACTIVE) throw emailInUse();
        var delivery =
                emails.findById(account.getId()).orElseThrow(IdentityServiceImpl::invalidLink);
        var latest = latest(delivery);
        if (latest != null && latest.getStatus() == OutboundEmail.Status.PENDING) {
            return pending(account, delivery, latest);
        }
        if (clock.instant().isBefore(resendAvailableAt(delivery, latest))) {
            throw new IdentityException(
                    429,
                    "RESEND_COOLDOWN",
                    "Wait 60 seconds after the previous email before requesting another.");
        }
        delivery.setLatestEmailId(outbox.verification(account.getId(), email));
        delivery.setNextAttemptAt(null);
        account.setUpdatedAt(clock.instant());
        return pending(account);
    }

    private OutboundEmail latest(VerificationEmailEntity verification) {
        return verification.getLatestEmailId() == null
                ? null
                : outboundEmails.findById(verification.getLatestEmailId()).orElseThrow();
    }

    private Instant resendAvailableAt(VerificationEmailEntity verification, OutboundEmail latest) {
        Instant when = verification.getLastSentAt();
        if (latest != null) {
            when = later(when, latest.getCreatedAt());
            when = later(when, latest.getAcceptedAt());
        }
        return when == null ? clock.instant() : when.plusSeconds(60);
    }

    static Instant later(Instant first, Instant second) {
        return first == null || (second != null && second.isAfter(first)) ? second : first;
    }

    private PendingView pending(AccountEntity account) {
        var delivery =
                emails.findById(account.getId()).orElseThrow(IdentityServiceImpl::invalidLink);
        return pending(account, delivery, latest(delivery));
    }

    private PendingView pending(
            AccountEntity account, VerificationEmailEntity delivery, OutboundEmail latest) {
        return new PendingView(
                account.getEmail(),
                resendAvailableAt(delivery, latest),
                latest == null ? delivery.getStatus().name() : latest.getStatus().name(),
                delivery.getLatestEmailId());
    }

    @Override
    @Transactional(readOnly = true)
    public PendingView deliveryStatus(String rawEmail) {
        return pending(
                accounts.findByEmail(normalizeEmail(rawEmail))
                        .orElseThrow(IdentityServiceImpl::invalidLink));
    }

    @Override
    @Transactional
    public VerificationView verify(Long id, String token) {
        String email = accounts.emailForId(id).orElseThrow(IdentityServiceImpl::invalidLink);
        locks.lock("email:" + email);
        var account = accounts.findById(id).orElseThrow(IdentityServiceImpl::invalidLink);
        var delivery = emails.findById(id).orElseThrow(IdentityServiceImpl::invalidLink);
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw invalidLink();
        }
        String hash = hash(token);
        boolean current =
                delivery.getTokenHash() != null
                        && MessageDigest.isEqual(
                                hash.getBytes(StandardCharsets.US_ASCII),
                                delivery.getTokenHash().getBytes(StandardCharsets.US_ASCII));
        var candidate =
                current || account.isActive()
                        ? null
                        : outboundEmails
                                .findByTokenHash(hash)
                                .filter(emailMessage -> id.equals(emailMessage.getAccountId()))
                                .orElse(null);
        Instant expiry =
                current
                        ? delivery.getTokenExpiresAt()
                        : candidate == null
                                ? null
                                : candidate.provisionalExpiry(delivery.getLastSentAt());
        if (expiry == null) {
            throw invalidLink();
        }
        if (!clock.instant().isBefore(expiry)) {
            throw new IdentityException(
                    400,
                    "EXPIRED_VERIFICATION",
                    "This link has expired. Request a new verification email.");
        }
        if (account.isActive()) return new VerificationView("ALREADY_VERIFIED", null);
        if (account.getStatus() != AccountStatus.PENDING) throw invalidLink();
        if (!current) {
            delivery.setTokenHash(hash);
            delivery.setTokenExpiresAt(expiry);
            delivery.setLastSentAt(expiry.minus(candidate.linkLifetime()));
        }
        account.setStatus(AccountStatus.ACTIVE);
        account.setUpdatedAt(clock.instant());
        workspaces.provisionDefault(account);
        return new VerificationView("VERIFIED", workspaces.view(account, null));
    }

    @Override
    @Transactional(readOnly = true)
    public CredentialsView credentials(String email) {
        return accounts.findByEmail(normalizeEmail(email))
                .map(
                        a ->
                                new CredentialsView(
                                        a.getEmail(), a.getPasswordHash(), "USER", a.isActive()))
                .orElse(null);
    }

    @Override
    @Transactional
    public AccountView current(String email) {
        // Repair older active accounts that predate automatic default assignment.
        // A revoked existing default is never replaced during session recovery.
        locks.lock("email:" + normalizeEmail(email));
        var account = workspaces.account(email);
        workspaces.provisionDefault(account);
        return workspaces.view(account, null);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView current(String email, Long workspaceId) {
        var membership = workspaces.require(email, workspaceId, false);
        return workspaces.view(membership.getAccount(), workspaceId);
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
