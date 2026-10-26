package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.api.dto.*;
import com.theeverthread.identity.api.exception.EmailDeliveryException;
import com.theeverthread.identity.api.service.InvitationService;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

@Service
@lombok.RequiredArgsConstructor
public class InvitationServiceImpl implements InvitationService {
    private final InvitationTransactions transactions;
    private final InvitationMail mail;
    private final SecureRandom random = new SecureRandom();

    @Override
    public InvitationPageView history(String actor, Long workspaceId, int page) {
        return transactions.history(actor, workspaceId, page);
    }

    public java.util.List<InvitationView> sent(String actor, Long workspaceId) {
        return transactions.sent(actor, workspaceId);
    }

    public java.util.List<ActiveMemberView> members(String actor, Long workspaceId) {
        return transactions.members(actor, workspaceId);
    }

    public InvitationView resend(String actor, Long workspaceId, Long id) {
        String token = token();
        var claim = transactions.claimResend(actor, workspaceId, id, token);
        try {
            String messageId = mail.send(claim, token);
            return transactions.completeResend(id, token, messageId, null);
        } catch (EmailDeliveryException failure) {
            return transactions.completeResend(id, token, null, failure.failureCode());
        }
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public InvitationView send(String actor, Long workspaceId, InvitationRequest request) {
        String token = token();
        var claim = transactions.claim(actor, workspaceId, request, token);
        if (claim.workspaceName() == null) {
            return claim.view();
        }
        String messageId;
        try {
            messageId = mail.send(claim, token);
        } catch (EmailDeliveryException failure) {
            return transactions.complete(claim.view().id(), token, null, failure.failureCode());
        }
        return transactions.complete(claim.view().id(), token, messageId, null);
    }

    public InvitationView status(String actor, Long workspaceId, InvitationRequest request) {
        return transactions.status(actor, workspaceId, request);
    }
}
