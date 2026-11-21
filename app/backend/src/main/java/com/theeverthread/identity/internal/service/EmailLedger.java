package com.theeverthread.identity.internal.service;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.*;

@Service
@RequiredArgsConstructor
public class EmailLedger {
    private final JdbcTemplate db;
    private final com.theeverthread.identity.internal.repository.RegistrationLocks locks;

    public String status(boolean invitation, long id, String fallback) {
        String column = invitation ? "invitation_id" : "verification_id";
        return db
                .queryForList(
                        "SELECT CASE WHEN outcome='FAILED' THEN 'DELIVERY_FAILED' ELSE"
                            + " coalesce(outcome,state) END FROM email_outbox WHERE "
                                + column
                                + "=? ORDER BY id DESC LIMIT 1",
                        String.class,
                        id)
                .stream()
                .findFirst()
                .orElse(fallback);
    }

    public void cancel(boolean invitation, long id) {
        String column = invitation ? "invitation_id" : "verification_id";
        db.update(
                "UPDATE email_outbox SET state='CANCELLED',claim=null,payload=null WHERE "
                        + column
                        + "=? AND state IN ('QUEUED','SENDING','REVIEW')",
                id);
    }

    public boolean validCandidate(boolean invitation, long id, String hash) {
        String column = invitation ? "invitation_id" : "verification_id";
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT coalesce((SELECT token_hash=? AND state IN"
                            + " ('SENDING','QUEUED','REVIEW','ACCEPTED') AND expires_at>now() FROM"
                            + " email_outbox WHERE "
                                + column
                                + "=? ORDER BY id DESC LIMIT 1),false)",
                        Boolean.class,
                        hash,
                        id));
    }

    record Job(UUID key, UUID claim, byte[] payload, int attempts) {}

    @Transactional
    public List<Job> claim() {
        return db.query(
                """
                UPDATE email_outbox SET claim=gen_random_uuid(),state='SENDING',attempts=attempts+1,
                    next_attempt=now()+interval '1 minute',first_attempt=coalesce(first_attempt,now())
                WHERE id=(SELECT id FROM email_outbox WHERE state IN ('QUEUED','SENDING')
                    AND next_attempt<=now() AND expires_at>now()
                    AND (first_attempt IS NULL OR first_attempt>now()-interval '23 hours')
                    ORDER BY next_attempt,id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING message_key,claim,payload,attempts
                """,
                (rs, n) ->
                        new Job(
                                rs.getObject(1, UUID.class),
                                rs.getObject(2, UUID.class),
                                rs.getBytes(3),
                                rs.getInt(4)));
    }

    public void expire() {
        db.update(
                """
                UPDATE email_outbox SET state='REVIEW',claim=null,payload=null,reason='Retry deadline reached'
                WHERE state IN ('QUEUED','SENDING') AND (expires_at<=now() OR first_attempt<=now()-interval '23 hours')
                """);
    }

    public void retry(Job job, int seconds) {
        db.update(
                "UPDATE email_outbox SET next_attempt=now()+make_interval(secs=>?),claim=null WHERE"
                    + " claim=?",
                seconds,
                job.claim());
    }

    @Transactional
    public void blocked(Job job, String reason) {
        var ids =
                db.queryForList(
                        "UPDATE email_outbox SET state='BLOCKED',reason=?,claim=null,payload=null"
                            + " WHERE claim=? RETURNING invitation_id",
                        Long.class,
                        reason,
                        job.claim());
        for (Long id : ids)
            if (id != null) {
                db.update(
                        """
                        UPDATE team_invitation SET replacement_token_hash=null,replacement_expires_at=null,failure_code='SEND_BLOCKED',
                        status=CASE WHEN status='SENDING' THEN 'FAILED' ELSE status END
                        WHERE id=? AND status<>'ACCEPTED' AND EXISTS(SELECT 1 FROM email_outbox
                        WHERE message_key=? AND token_hash IN (team_invitation.token_hash,team_invitation.replacement_token_hash))
                        """,
                        id,
                        job.key());
            }
    }

    @Transactional
    public void accepted(UUID key, String providerId) {
        locks.lock("email-event:" + providerId);
        int changed =
                db.update(
                        """
                        UPDATE email_outbox SET state='ACCEPTED',provider_id=?,claim=null,payload=null,next_check=now()+interval '10 minutes'
                        WHERE message_key=? AND state IN ('SENDING','QUEUED','REVIEW')
                        """,
                        providerId,
                        key);
        if (changed == 0) return;
        db.update(
                """
                UPDATE verification_email v SET status='SMTP_ACCEPTED',last_sent_at=now(),token_hash=o.token_hash,
                token_expires_at=o.expires_at,smtp_message_id=o.provider_id,next_attempt_at=null,failure_code=null
                FROM email_outbox o WHERE o.message_key=? AND v.account_id=o.verification_id
                AND NOT EXISTS(SELECT 1 FROM email_outbox later WHERE later.verification_id=v.account_id AND later.id>o.id)
                """,
                key);
        db.update(
                """
                UPDATE team_invitation i SET status='SMTP_ACCEPTED',smtp_message_id=o.provider_id,sent_at=now(),
                token_hash=o.token_hash,expires_at=o.expires_at,replacement_token_hash=null,replacement_expires_at=null,failure_code=null
                FROM email_outbox o WHERE o.message_key=? AND i.id=o.invitation_id AND i.status IN ('SENDING','SMTP_ACCEPTED','FAILED')
                AND o.token_hash IN (i.token_hash,i.replacement_token_hash)
                AND NOT EXISTS(SELECT 1 FROM email_outbox later WHERE later.invitation_id=i.id AND later.id>o.id)
                """,
                key);
        applyEvents(providerId);
    }

    @Transactional
    public void observe(DeliveryProvider.Notice event) {
        locks.lock("email-event:" + event.providerId());
        db.update(
                """
                INSERT INTO email_event(event_key,provider_id,message_key,outcome,occurred_at,reason)
                VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """,
                event.eventKey(),
                event.providerId(),
                event.key(),
                event.outcome(),
                Timestamp.from(event.at()),
                event.reason());
        if (event.key() != null) accepted(event.key(), event.providerId());
        applyEvents(event.providerId());
    }

    private void applyEvents(String providerId) {
        db.update(
                """
                UPDATE email_outbox o SET outcome=upper(e.outcome),reason=e.reason,event_at=e.occurred_at,
                next_check=CASE WHEN e.outcome='delivery_delayed' THEN now()+interval '1 hour' END
                FROM (SELECT outcome,reason,occurred_at FROM email_event WHERE provider_id=?
                    AND outcome IN ('delivery_delayed','delivered','bounced','suppressed','failed','complained')
                    ORDER BY (outcome='complained') DESC,(outcome<>'delivery_delayed') DESC,occurred_at DESC,event_key DESC LIMIT 1) e
                WHERE o.provider_id=?
                """,
                providerId,
                providerId);
    }

    public List<String> pendingChecks() {
        return db.queryForList(
                """
                UPDATE email_outbox SET next_check=now()+interval '1 hour' WHERE id IN
                (SELECT id FROM email_outbox WHERE next_check<=now() ORDER BY next_check LIMIT 25 FOR UPDATE SKIP LOCKED)
                RETURNING provider_id
                """,
                String.class);
    }
}
