package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.internal.entity.*;
import com.theeverthread.identity.internal.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;

/**
 * Adopt pending SMTP requests at cutover. An ambiguous legacy SENDING row stays available for
 * investigation.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "email.provider", havingValue = "resend")
class LegacyVerificationQueue {
    private final VerificationEmailTransactions transactions;
    private final VerificationEmailRepository emails;
    private final QueuedEmail queue;
    private final Clock clock;

    @Transactional
    public void enqueueDue() {
        for (long id : transactions.due()) {
            var claim = transactions.claim(id);
            if (claim == null) continue;
            queue.verification(id, claim.recipient(), clock.instant().plus(Duration.ofHours(24)));
            emails.findById(id).orElseThrow().setStatus(EmailDeliveryStatus.PENDING);
        }
    }
}
