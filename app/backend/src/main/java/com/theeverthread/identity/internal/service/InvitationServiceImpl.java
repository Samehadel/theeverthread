package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.api.dto.*;
import com.theeverthread.identity.api.service.InvitationService;

import org.springframework.stereotype.Service;

@Service
@lombok.RequiredArgsConstructor
public class InvitationServiceImpl implements InvitationService {
    private final InvitationTransactions transactions;

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
        return transactions.resend(actor, workspaceId, id);
    }

    public InvitationView send(String actor, Long workspaceId, InvitationRequest request) {
        return transactions.send(actor, workspaceId, request);
    }

    public InvitationView status(String actor, Long workspaceId, InvitationRequest request) {
        return transactions.status(actor, workspaceId, request);
    }
}
