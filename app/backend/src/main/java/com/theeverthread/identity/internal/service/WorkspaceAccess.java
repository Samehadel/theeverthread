package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.api.dto.IdentityViews.*;
import com.theeverthread.identity.api.exception.IdentityException;
import com.theeverthread.identity.internal.entity.*;
import com.theeverthread.identity.internal.mapper.AccountMapper;
import com.theeverthread.identity.internal.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.*;

/** Called inside the owning use-case transaction; email locks serialize default provisioning. */
@Service
@RequiredArgsConstructor
public class WorkspaceAccess {
    private final WorkspaceRepository workspaces;
    private final MembershipRepository memberships;
    private final AccountRepository accounts;
    private final AccountMapper mapper;
    private final RegistrationLocks locks;

    public WorkspaceEntity provisionDefault(AccountEntity account) {
        if (account.getDefaultWorkspace() != null) return account.getDefaultWorkspace();
        locks.lock("email:" + account.getEmail());
        String name = "My workspace";
        int suffix = 2;
        while (memberships.ownedNameExists(account.getId(), name, -1L)) {
            name = "My workspace (" + suffix + ")";
            suffix++;
        }
        var workspace = createOwned(account, name);
        account.setDefaultWorkspace(workspace);
        return workspace;
    }

    public WorkspaceEntity createOwned(AccountEntity account, String name) {
        requireUniqueName(account, name, -1L);
        var workspace = new WorkspaceEntity();
        workspace.setName(name);
        workspace.setStatus(WorkspaceStatus.ACTIVE);
        workspace = workspaces.save(workspace);
        join(account, workspace, "OWNER");
        return workspace;
    }

    /** Serializes all name changes for an owner, including automatic default provisioning. */
    public void requireUniqueName(AccountEntity account, String name, Long excludedId) {
        locks.lock("email:" + account.getEmail());
        if (memberships.ownedNameExists(account.getId(), name, excludedId)) {
            throw new IdentityException(
                    409,
                    "WORKSPACE_NAME_TAKEN",
                    "You already own a workspace with this name. Choose another name.");
        }
    }

    public void join(AccountEntity account, WorkspaceEntity workspace, String role) {
        if (memberships
                .findByAccountIdAndWorkspaceIdAndActiveTrue(account.getId(), workspace.getId())
                .isPresent()) return;
        var membership =
                memberships
                        .findByAccountIdAndWorkspaceId(account.getId(), workspace.getId())
                        .orElseGet(
                                () -> {
                                    var created = new MembershipEntity();
                                    return created;
                                });
        membership.setActive(true);
        membership.setAccount(account);
        membership.setWorkspace(workspace);
        membership.setRole(role);
        memberships.save(membership);
    }

    public AccountEntity account(String email) {
        return accounts.findByEmail(IdentityServiceImpl.normalizeEmail(email))
                .filter(AccountEntity::isActive)
                .orElseThrow(
                        () ->
                                new IdentityException(
                                        403,
                                        "ACCESS_DENIED",
                                        "Verify your email and log in to continue."));
    }

    public MembershipEntity require(String email, Long workspaceId, boolean owner) {
        var account = account(email);
        if (workspaceId == null) throw denied();
        var member =
                memberships
                        .findByAccountIdAndWorkspaceIdAndActiveTrue(account.getId(), workspaceId)
                        .orElseThrow(WorkspaceAccess::denied);
        if (member.getWorkspace().getStatus() != WorkspaceStatus.ACTIVE
                || (owner && !"OWNER".equals(member.getRole()))) throw denied();
        return member;
    }

    public AccountView view(AccountEntity account, Long selected) {
        var available =
                memberships
                        .findByAccountIdAndActiveTrueOrderByWorkspaceNameAsc(account.getId())
                        .stream()
                        .filter(m -> m.getWorkspace().getStatus() == WorkspaceStatus.ACTIVE)
                        .map(mapper::workspace)
                        .toList();
        Long preferred =
                selected != null
                        ? selected
                        : account.getDefaultWorkspace() == null
                                ? null
                                : account.getDefaultWorkspace().getId();
        var active =
                available.stream().filter(w -> w.id().equals(preferred)).findFirst().orElse(null);
        if (selected != null && active == null) throw denied();
        if (active == null && !available.isEmpty()) active = available.getFirst();
        return new AccountView(
                account.getId(),
                account.getFullName(),
                account.getEmail(),
                active == null ? null : active.id(),
                active == null ? null : active.name(),
                active == null ? null : active.role(),
                available,
                account.getDefaultWorkspace() == null
                        ? null
                        : account.getDefaultWorkspace().getId());
    }

    private static IdentityException denied() {
        return new IdentityException(
                403, "WORKSPACE_ACCESS_DENIED", "You do not have access to this workspace.");
    }
}
