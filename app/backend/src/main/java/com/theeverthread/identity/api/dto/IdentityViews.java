package com.theeverthread.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public final class IdentityViews {
    private IdentityViews() {}

    public record PendingView(String email, Instant resendAvailableAt, String deliveryStatus) {}

    public record VerificationView(String status, AccountView account) {}

    public record WorkspaceView(
            @JsonFormat(shape = JsonFormat.Shape.STRING) Long id, String name, String role) {}

    public record AccountView(
            @JsonFormat(shape = JsonFormat.Shape.STRING) Long id,
            String fullName,
            String email,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Long workspaceId,
            String workspaceName,
            String accessRole,
            java.util.List<WorkspaceView> workspaces,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Long defaultWorkspaceId) {}

    public record CredentialsView(
            String email, String passwordHash, String accessRole, boolean active) {}
}
