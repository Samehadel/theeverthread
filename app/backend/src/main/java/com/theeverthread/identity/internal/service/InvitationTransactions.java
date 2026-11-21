package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.api.dto.*;
import com.theeverthread.identity.api.exception.IdentityException;
import com.theeverthread.identity.internal.entity.*;
import com.theeverthread.identity.internal.repository.*;

import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;

import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** Persists invitation state and the email obligation in a single transaction. */
@Service
@RequiredArgsConstructor
public class InvitationTransactions {
    private final AccountRepository accounts;
    private final InvitationRepository invitations;
    private final RegistrationLocks locks;
    private final Clock clock;
    private final Validator validator;
    private final Environment configuration;
    private final WorkspaceAccess access;
    private final MembershipRepository memberships;
    private final OutboundEmailRepository outboundEmails;
    private final EmailOutbox outbox;

    private MembershipEntity administrator(String email, Long workspaceId) {
        return access.require(email, workspaceId, true);
    }

    private String recipient(InvitationRequest request) {
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new IdentityException(
                    400, "INVALID_REQUEST", violations.iterator().next().getMessage());
        }
        return IdentityServiceImpl.normalizeEmail(request.email());
    }

    @Transactional
    public InvitationView send(String actor, Long workspaceId, InvitationRequest request) {
        var admin = administrator(actor, workspaceId);
        String email = recipient(request);
        // Acceptance must acquire these locks in the same order.
        locks.lock("email:" + email);
        locks.lock("workspace:" + admin.getWorkspace().getId());
        requireEligibleRecipient(admin, email);
        var existing = invitations.findByWorkspaceIdAndEmail(workspaceId, email).orElse(null);
        if (existing != null) {
            if ("ACCEPTED".equals(existing.getStatus())) {
                throw new IdentityException(
                        409, "INVITATION_USED", "This invitation has already been accepted.");
            }
            var latest = latest(existing);
            if (!"REVOKED".equals(existing.getStatus())
                    && (hasUsableLink(existing) || hasProvisionalLink(existing, latest))) {
                throw new IdentityException(
                        409, "INVITATION_PENDING", "Invitation already pending");
            }
            if (inFlight(latest) && !"REVOKED".equals(existing.getStatus())) {
                return view(existing, latest);
            }
        }
        requireCapacity(admin);
        var invitation = existing == null ? new InvitationEntity() : existing;
        if (existing != null && !"REVOKED".equals(invitation.getStatus())) {
            checkCooldown(invitation, latest(invitation));
        }
        invitation.setWorkspace(admin.getWorkspace());
        invitation.setEmail(email);
        invitation.setAdministratorId(admin.getAccount().getId());
        invitation.setAdministratorName(admin.getAccount().getFullName());
        invitation.setStatus("PENDING");
        invitation.setTokenHash(null);
        invitation.setAttemptedAt(clock.instant());
        invitation.setExpiresAt(clock.instant().plus(Duration.ofDays(7)));
        invitation.setSentAt(null);
        invitations.saveAndFlush(invitation);
        return queue(invitation, admin, null);
    }

    private void requireEligibleRecipient(MembershipEntity admin, String email) {
        var recipient = accounts.findByEmail(email);
        if (recipient.isPresent()
                && memberships
                        .findByAccountIdAndWorkspaceIdAndActiveTrue(
                                recipient.get().getId(), admin.getWorkspace().getId())
                        .isPresent()) {
            throw new IdentityException(
                    409, "ALREADY_MEMBER", "Already a member of this workspace.");
        }
    }

    private void requireCapacity(MembershipEntity admin) {
        int defaultLimit =
                configuration.getProperty(
                        "theeverthread.invitations.max-active-accounts", Integer.class, 10);
        if (defaultLimit < 1) {
            throw new IllegalArgumentException("Active-account cap must be positive");
        }
        int limit =
                admin.getWorkspace().getMaxActiveAccounts() == null
                        ? defaultLimit
                        : admin.getWorkspace().getMaxActiveAccounts();
        if (memberships.countByWorkspaceIdAndActiveTrue(admin.getWorkspace().getId()) >= limit) {
            throw new IdentityException(
                    409,
                    "ACCOUNT_LIMIT",
                    "Your workspace has reached its limit of "
                            + limit
                            + " active accounts. You cannot send invitations.");
        }
    }

    @Transactional(readOnly = true)
    public List<InvitationView> sent(String actor, Long workspaceId) {
        var admin = administrator(actor, workspaceId);
        return invitations.managementRecords(admin.getWorkspace().getId(), clock.instant()).stream()
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ActiveMemberView> members(String actor, Long workspaceId) {
        var admin = administrator(actor, workspaceId);
        return memberships.findByWorkspaceIdAndActiveTrue(admin.getWorkspace().getId()).stream()
                .filter(m -> m.getAccount().isActive())
                .sorted(
                        java.util.Comparator.comparing(
                                        (MembershipEntity m) -> m.getAccount().getUpdatedAt())
                                .reversed()
                                .thenComparing(m -> m.getAccount().getId()))
                .map(
                        m ->
                                new ActiveMemberView(
                                        m.getAccount().getFullName(),
                                        m.getAccount().getEmail(),
                                        m.getRole()))
                .toList();
    }

    @Transactional
    public InvitationView resend(String actor, Long workspaceId, Long id) {
        var admin = administrator(actor, workspaceId);
        String email = invitations.emailForId(id).orElseThrow(this::notFound);
        locks.lock("email:" + email);
        locks.lock("workspace:" + workspaceId);
        var invitation = invitations.findById(id).orElseThrow(this::notFound);
        if (!invitation.getWorkspace().getId().equals(workspaceId)) {
            throw notFound();
        }
        var latest = latest(invitation);
        if ("ACCEPTED".equals(invitation.getStatus())
                || "REVOKED".equals(invitation.getStatus())
                || (!canResend(invitation, latest) && !inFlight(latest))) {
            throw new IdentityException(
                    409, "INVITATION_UNUSABLE", "This invitation cannot be resent.");
        }
        requireEligibleRecipient(admin, email);
        requireCapacity(admin);
        if (inFlight(latest)) {
            throw new IdentityException(
                    409, "RESEND_IN_PROGRESS", "An email is already queued for this invitation.");
        }
        checkCooldown(invitation, latest);
        return queue(invitation, admin, latest == null ? null : latest.getSeriesId());
    }

    private InvitationView queue(
            InvitationEntity invitation, MembershipEntity admin, Long seriesId) {
        var id =
                outbox.invitation(
                        invitation.getId(),
                        seriesId,
                        invitation.getEmail(),
                        admin.getWorkspace().getName(),
                        admin.getAccount().getFullName());
        invitation.setLatestEmailId(id);
        return view(invitation);
    }

    private IdentityException notFound() {
        return new IdentityException(
                404, "INVITATION_NOT_FOUND", "No invitation was found in your workspace.");
    }

    private OutboundEmail latest(InvitationEntity invitation) {
        return invitation.getLatestEmailId() == null
                ? null
                : outboundEmails.findById(invitation.getLatestEmailId()).orElseThrow();
    }

    private boolean hasUsableLink(InvitationEntity invitation) {
        return "READY".equals(invitation.getStatus())
                && clock.instant().isBefore(invitation.getExpiresAt());
    }

    private boolean inFlight(OutboundEmail email) {
        return email != null && email.getStatus() == OutboundEmail.Status.PENDING;
    }

    private boolean canResend(InvitationEntity invitation, OutboundEmail latest) {
        if (inFlight(latest)
                || "ACCEPTED".equals(invitation.getStatus())
                || "REVOKED".equals(invitation.getStatus())) {
            return false;
        }
        return hasUsableLink(invitation)
                || "FAILED".equals(invitation.getStatus())
                || ("PENDING".equals(invitation.getStatus()) && latest != null)
                || hasProvisionalLink(invitation, latest);
    }

    private boolean hasProvisionalLink(InvitationEntity invitation, OutboundEmail latest) {
        return latest != null
                && latest.getSeriesId() != null
                && outboundEmails.hasProvisionalInvitation(
                        latest.getSeriesId(),
                        clock.instant().minus(Duration.ofDays(7)),
                        invitation.getSentAt() == null ? Instant.EPOCH : invitation.getSentAt());
    }

    private Instant resendAvailableAt(InvitationEntity invitation, OutboundEmail email) {
        Instant when =
                IdentityServiceImpl.later(invitation.getAttemptedAt(), invitation.getSentAt());
        when = IdentityServiceImpl.later(when, invitation.getReplacementAttemptedAt());
        if (email != null) {
            when = IdentityServiceImpl.later(when, email.getCreatedAt());
            when = IdentityServiceImpl.later(when, email.getAcceptedAt());
        }
        return when.plusSeconds(60);
    }

    private void checkCooldown(InvitationEntity invitation, OutboundEmail latest) {
        if (clock.instant().isBefore(resendAvailableAt(invitation, latest))) {
            throw new IdentityException(
                    429, "RESEND_COOLDOWN", "Please wait 60 seconds between invitation sends.");
        }
    }

    @Transactional(readOnly = true)
    public InvitationPageView history(String actor, Long workspaceId, int page) {
        var admin = administrator(actor, workspaceId);
        if (page < 0 || page > Integer.MAX_VALUE / 25) {
            throw new IdentityException(400, "INVALID_REQUEST", "Choose a valid invitation page.");
        }
        var invitationsPage =
                invitations.findByWorkspaceId(
                        admin.getWorkspace().getId(),
                        PageRequest.of(
                                page, 25, Sort.by(Sort.Direction.DESC, "attemptedAt", "id")));
        return new InvitationPageView(
                invitationsPage.getContent().stream().map(this::view).toList(),
                invitationsPage.hasNext());
    }

    @Transactional(readOnly = true)
    public InvitationView status(String actor, Long workspaceId, InvitationRequest request) {
        var admin = administrator(actor, workspaceId);
        return invitations
                .findByWorkspaceIdAndEmail(admin.getWorkspace().getId(), recipient(request))
                .map(this::view)
                .orElseThrow(
                        () ->
                                new IdentityException(
                                        404,
                                        "INVITATION_NOT_FOUND",
                                        "No invitation was found. You can retry sending."));
    }

    private InvitationView view(InvitationEntity invitation) {
        return view(invitation, latest(invitation));
    }

    private InvitationView view(InvitationEntity invitation, OutboundEmail latest) {
        var deliveryStatus = latest == null ? OutboundEmail.Status.UNKNOWN : latest.getStatus();
        String status = invitation.getStatus();
        String message =
                switch (deliveryStatus) {
                    case PENDING ->
                            "Email queued. Temporary sending failures are retried automatically.";
                    case PROVIDER_ACCEPTED ->
                            "The email provider accepted the invitation. Delivery is pending.";
                    case DELIVERED -> "The recipient’s mail server accepted the invitation.";
                    case FAILED ->
                            hasUsableLink(invitation)
                                    ? "The replacement email failed. The previous link is still"
                                            + " valid until its original expiry."
                                    : "The email could not be delivered. Check the address and try"
                                            + " again.";
                    case UNKNOWN -> "The final email delivery outcome is unknown.";
                    case CANCELLED -> "The invitation email is no longer needed.";
                };
        if ("ACCEPTED".equals(status)) {
            message = "This invitation has been accepted.";
        }
        return new InvitationView(
                invitation.getId(),
                invitation.getEmail(),
                status,
                hasUsableLink(invitation) || "ACCEPTED".equals(status)
                        ? invitation.getExpiresAt()
                        : null,
                message,
                invitation.getSentAt(),
                resendAvailableAt(invitation, latest),
                inFlight(latest) && invitation.getTokenHash() != null,
                deliveryStatus == OutboundEmail.Status.FAILED,
                deliveryStatus.name(),
                canResend(invitation, latest),
                invitation.getLatestEmailId());
    }
}
