package com.theeverthread.identity.internal.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@RequiredArgsConstructor
public class EmailDeliveryWorker {
    private final EmailDeliveryTransactions transactions;
    private final LegacyVerificationQueue legacy;
    private final EmailOutbox outbox;
    private final ResendClient resend;
    private final Clock clock;

    public void runOnce() {
        for (var id : transactions.dueSubmissions()) {
            var claim = transactions.claim(id);
            if (claim != null) {
                submit(claim);
            }
        }
        for (var id : transactions.dueLookups()) {
            var claim = transactions.claimLookup(id);
            if (claim != null) {
                transactions.reconciled(claim, resend.lookup(claim.providerId()));
            }
        }
        legacy.enqueueDue();
    }

    private void submit(EmailDeliveryTransactions.SubmissionClaim claim) {
        byte[] payload;
        try {
            payload = outbox.decrypt(claim.id(), claim.payload());
        } catch (IllegalStateException exception) {
            transactions.submitted(
                    claim,
                    new ResendClient.Rejected("PAYLOAD_KEY_UNAVAILABLE", null, true, false, 0));
            return;
        }
        if (clock.instant().isBefore(claim.validUntil())) {
            transactions.submitted(claim, resend.submit(claim.id(), payload));
        }
    }
}
