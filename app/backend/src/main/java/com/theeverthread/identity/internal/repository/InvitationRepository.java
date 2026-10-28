package com.theeverthread.identity.internal.repository;

import com.theeverthread.identity.internal.entity.InvitationEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.*;

public interface InvitationRepository extends JpaRepository<InvitationEntity, Long> {
    @Query("select i.email from InvitationEntity i where i.id = :id")
    Optional<String> emailForId(Long id);

    @Query(
            "select i from InvitationEntity i where i.workspace.id = :workspaceId and"
                    + " (i.status in ('FAILED', 'ACCEPTED') or"
                    + " (i.status = 'SMTP_ACCEPTED' and i.expiresAt > :now))"
                    + " order by coalesce(i.sentAt, i.attemptedAt) desc, i.id desc")
    List<InvitationEntity> managementRecords(Long workspaceId, java.time.Instant now);

    Slice<InvitationEntity> findByWorkspaceId(Long workspaceId, Pageable page);

    Optional<InvitationEntity> findByWorkspaceIdAndEmail(Long workspaceId, String email);
}
