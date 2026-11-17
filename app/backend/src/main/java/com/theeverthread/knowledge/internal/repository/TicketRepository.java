package com.theeverthread.knowledge.internal.repository;

import com.theeverthread.knowledge.internal.entity.TicketEntity;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<TicketEntity, Long> {
    Optional<TicketEntity> findByAuthorIdAndSubmissionId(Long authorId, UUID submissionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TicketEntity t where t.id = :id and t.workspaceId = :workspaceId")
    Optional<TicketEntity> findForUpdate(Long id, Long workspaceId);

    Optional<TicketEntity> findByIdAndWorkspaceId(Long id, Long workspaceId);
}
