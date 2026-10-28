package com.theeverthread.identity.internal.repository;

import com.theeverthread.identity.internal.entity.MembershipEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.*;

public interface MembershipRepository extends JpaRepository<MembershipEntity, Long> {
    @Query(
            """
            select count(m) > 0 from MembershipEntity m
            where m.account.id = :accountId and m.role = 'OWNER' and m.active = true
              and lower(trim(m.workspace.name)) = lower(:name)
              and m.workspace.id <> :excludedId
            """)
    boolean ownedNameExists(Long accountId, String name, Long excludedId);

    Optional<MembershipEntity> findByAccountIdAndWorkspaceId(Long accountId, Long workspaceId);

    Optional<MembershipEntity> findByAccountIdAndWorkspaceIdAndActiveTrue(
            Long accountId, Long workspaceId);

    List<MembershipEntity> findByAccountIdAndActiveTrueOrderByWorkspaceNameAsc(Long accountId);

    List<MembershipEntity> findByWorkspaceIdAndActiveTrue(Long workspaceId);

    long countByWorkspaceIdAndActiveTrue(Long workspaceId);
}
