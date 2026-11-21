package com.theeverthread.identity.internal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "email.provider", havingValue = "resend")
class EmailDispatcher {
    private final DeliveryProvider provider;
    private final EmailLedger ledger;
    private final QueuedEmail queue;
    private final Clock clock;

    @Scheduled(
            initialDelayString = "${email.initial-delay:0}",
            fixedDelayString = "${email.send-delay:5000}")
    public void send() {
        ledger.expire();
        for (int i = 0; i < 25; i++) {
            var jobs = ledger.claim();
            if (jobs.isEmpty()) break;
            var job = jobs.getFirst();
            try {
                String providerId =
                        provider.submit(job.key(), queue.decrypt(job.key(), job.payload()));
                ledger.accepted(job.key(), providerId);
            } catch (DeliveryProvider.SubmissionFailure failure) {
                if (failure.retryable) ledger.retry(job, Math.max(failure.waitSeconds, delay(job)));
                else ledger.blocked(job, failure.getMessage());
                if (failure.throttle) break;
            } catch (org.springframework.web.client.ResourceAccessException e) {
                ledger.retry(job, delay(job));
            }
        }
    }

    private int delay(EmailLedger.Job job) {
        return Math.min(900, 10 << Math.min(job.attempts(), 7));
    }

    @Scheduled(
            initialDelayString = "${email.initial-delay:0}",
            fixedDelayString = "${email.check-delay:300000}")
    public void reconcile() {
        for (String id : ledger.pendingChecks()) {
            try {
                String outcome = provider.lookup(id);
                if (Set.of(
                                "delivered",
                                "bounced",
                                "failed",
                                "suppressed",
                                "complained",
                                "delivery_delayed")
                        .contains(outcome)) {
                    ledger.observe(
                            new DeliveryProvider.Notice(
                                    "lookup/" + id + "/" + outcome,
                                    id,
                                    null,
                                    outcome,
                                    clock.instant(),
                                    ""));
                }
            } catch (org.springframework.web.client.RestClientException ex) {
                log.warn("Unable to check delivery for provider message {}", id);
            }
        }
    }
}
