package com.theeverthread.identity.internal.service;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "theeverthread.registration.delivery.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class EmailDeliveryScheduler {
    private final EmailDeliveryWorker worker;

    @Scheduled(cron = "${theeverthread.registration.delivery.cron:*/5 * * * * *}")
    public void scheduledRun() {
        worker.runOnce();
    }
}
