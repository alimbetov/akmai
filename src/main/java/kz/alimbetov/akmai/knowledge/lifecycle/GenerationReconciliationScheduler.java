package kz.alimbetov.akmai.knowledge.lifecycle;

import kz.alimbetov.akmai.config.ReconciliationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GenerationReconciliationScheduler {

    private final GenerationReconciliationService service;
    private final ReconciliationProperties properties;

    public GenerationReconciliationScheduler(
            GenerationReconciliationService service,
            ReconciliationProperties properties
    ) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${akmai.reconciliation.fixed-delay:PT5M}"
    )
    public void reconcile() {
        if (!properties.enabled()) {
            return;
        }
        while (service.reconcileBatch() == properties.batchSize()) {
            // Continue bounded batches until the current backlog is drained.
        }
    }
}
