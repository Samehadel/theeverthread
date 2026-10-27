package com.theeverthread.knowledge.internal.service;

import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ClassificationWorker {
    private final ClassificationTransactions transactions;
    private final KnowledgeAi ai;
    private final AttributeCatalog catalog;
    private final MeterRegistry metrics;

    public void runOnce() {
        for (int count = 0; count < 10; count++) {
            var work = transactions.claim();
            if (work == null) return;
            try {
                var definitions = catalog.definitions();
                var selections =
                        definitions.isEmpty()
                                ? List.<String>of()
                                : ai.classify(work.content(), definitions);
                transactions.complete(work, selections, definitions);
                metrics.counter("theeverthread.knowledge.classification", "result", "complete")
                        .increment();
            } catch (RuntimeException exception) {
                transactions.fail(work);
                metrics.counter(
                                "theeverthread.knowledge.classification",
                                "result",
                                "failed_attempt")
                        .increment();
            }
        }
    }
}
