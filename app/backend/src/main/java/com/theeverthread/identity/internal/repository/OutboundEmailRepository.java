package com.theeverthread.identity.internal.repository;

import com.theeverthread.identity.internal.entity.OutboundEmail;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboundEmailRepository extends JpaRepository<OutboundEmail, UUID> {
    @Query(
            "select e.id from OutboundEmail e where e.status = 'PENDING' and"
                    + " e.nextSubmissionAt <= :now order by e.nextSubmissionAt, e.id")
    List<UUID> dueSubmissions(Instant now, Pageable limit);

    @Query(
            "select e.id from OutboundEmail e where e.status = 'PROVIDER_ACCEPTED' and"
                    + " e.nextLookupAt <= :now order by e.nextLookupAt, e.id")
    List<UUID> dueLookups(Instant now, Pageable limit);

    @Query("select e.recipient from OutboundEmail e where e.id = :id")
    Optional<String> recipient(UUID id);

    @Query("select e.id from OutboundEmail e where e.providerId = :providerId")
    Optional<UUID> idForProvider(UUID providerId);

    @Query(
            """
            select count(e) > 0 from OutboundEmail e
            where e.seriesId = :seriesId
                and e.status in ('PROVIDER_ACCEPTED', 'DELIVERED', 'UNKNOWN')
                and coalesce(e.acceptedAt, e.firstAttemptAt) > :cutoff
                and e.createdAt > :confirmedAt
            """)
    boolean hasProvisionalInvitation(UUID seriesId, Instant cutoff, Instant confirmedAt);

    Optional<OutboundEmail> findByTokenHash(String tokenHash);
}
