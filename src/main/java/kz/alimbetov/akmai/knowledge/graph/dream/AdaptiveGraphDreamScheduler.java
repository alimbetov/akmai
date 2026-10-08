package kz.alimbetov.akmai.knowledge.graph.dream;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs on every pod, but coordinator lease election allows only one pod to do
 * Dream work for a graph/policy epoch.
 */
@Component
public class AdaptiveGraphDreamScheduler {

    private final AdaptiveGraphDreamCoordinator coordinator;

    public AdaptiveGraphDreamScheduler(
            AdaptiveGraphDreamCoordinator coordinator
    ) {
        this.coordinator = coordinator;
    }

    @Scheduled(
            cron = "${akmai.adaptive-graph.dream.cron:0 0 3 * * *}",
            zone = "${akmai.adaptive-graph.dream.zone:UTC}"
    )
    public void runScheduled() {
        coordinator.runOnce();
    }
}
