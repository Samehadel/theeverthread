package com.theeverthread.identity.internal.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "theeverthread.registration.delivery.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class VerificationEmailScheduler {
    private final VerificationEmailWorker worker;
    private final java.util.Optional<LegacyVerificationQueue> importer;

    public VerificationEmailScheduler(
            VerificationEmailWorker worker, java.util.Optional<LegacyVerificationQueue> importer) {
        this.importer = importer;
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${theeverthread.registration.delivery.delay:5000}")
    public void scheduledRun() {
        if (importer.isPresent()) importer.get().enqueueDue();
        else worker.runOnce();
    }
}
