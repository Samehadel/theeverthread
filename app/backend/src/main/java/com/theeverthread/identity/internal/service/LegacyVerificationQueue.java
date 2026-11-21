package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.internal.entity.AccountStatus;
import com.theeverthread.identity.internal.entity.EmailDeliveryStatus;
import com.theeverthread.identity.internal.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
class LegacyVerificationQueue {
    private final AccountRepository accounts;
    private final VerificationEmailRepository verifications;
    private final RegistrationLocks locks;
    private final EmailOutbox outbox;
    private final TransactionTemplate transaction;
    private final Clock clock;

    void enqueueDue() {
        var due =
                verifications.due(
                        List.of(EmailDeliveryStatus.PENDING, EmailDeliveryStatus.FAILED),
                        clock.instant(),
                        PageRequest.of(0, 25));
        for (Long id : due) {
            transaction.executeWithoutResult(ignored -> enqueue(id));
        }
    }

    private void enqueue(Long id) {
        var email = accounts.emailForId(id);
        if (email.isEmpty()) {
            return;
        }
        locks.lock("email:" + email.get());
        var verification = verifications.findById(id).orElse(null);
        if (verification == null
                || verification.getLatestEmailId() != null
                || verification.getNextAttemptAt() == null) {
            return;
        }
        if (accounts.findById(id).orElseThrow().getStatus() == AccountStatus.PENDING) {
            verification.setLatestEmailId(outbox.verification(id, email.get()));
        }
        verification.setNextAttemptAt(null);
    }
}
