package com.theeverthread.identity.internal.repository;

import com.theeverthread.identity.internal.entity.MembershipEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface MembershipRepository extends JpaRepository<MembershipEntity, Long> {
    Optional<MembershipEntity> findByAccountIdAndWorkspaceId(Long accountId, Long workspaceId);

    Optional<MembershipEntity> findByAccountIdAndWorkspaceIdAndActiveTrue(
            Long accountId, Long workspaceId);

    List<MembershipEntity> findByAccountIdAndActiveTrueOrderByWorkspaceNameAsc(Long accountId);

    List<MembershipEntity> findByWorkspaceIdAndActiveTrue(Long workspaceId);

    long countByWorkspaceIdAndActiveTrue(Long workspaceId);
}
