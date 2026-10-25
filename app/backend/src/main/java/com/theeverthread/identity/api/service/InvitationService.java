package com.theeverthread.identity.api.service;

import com.theeverthread.identity.api.dto.*;

public interface InvitationService {
    java.util.List<InvitationView> sent(String administratorEmail, Long workspaceId);

    java.util.List<ActiveMemberView> members(String administratorEmail, Long workspaceId);

    InvitationView resend(String administratorEmail, Long workspaceId, Long id);

    InvitationPageView history(String administratorEmail, Long workspaceId, int page);

    InvitationView send(String administratorEmail, Long workspaceId, InvitationRequest request);

    InvitationView status(String administratorEmail, Long workspaceId, InvitationRequest request);
}
