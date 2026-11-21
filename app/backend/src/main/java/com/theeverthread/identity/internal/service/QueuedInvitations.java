package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.api.dto.*;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "email.provider", havingValue = "resend")
class QueuedInvitations {
    private final InvitationTransactions invitations;
    private final QueuedEmail queue;

    @Transactional
    public InvitationView send(
            String actor, long workspace, InvitationRequest request, String token) {
        var claim = invitations.claim(actor, workspace, request, token);
        if (claim.workspaceName() != null) queue.invitation(claim, token);
        return claim.view();
    }

    @Transactional
    public InvitationView resend(String actor, long workspace, long id, String token) {
        var claim = invitations.claimResend(actor, workspace, id, token);
        queue.invitation(claim, token);
        return invitations.status(actor, workspace, new InvitationRequest(claim.view().email()));
    }
}
