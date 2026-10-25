package com.theeverthread.foundation.internal.security;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

interface RefreshSessionRepository extends JpaRepository<RefreshSession, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.id = :id")
    Optional<RefreshSession> lock(@Param("id") Long id);

    @Modifying
    @Query("delete from RefreshSession s where s.expiresAt <= :now")
    void deleteExpired(@Param("now") Instant now);
}
