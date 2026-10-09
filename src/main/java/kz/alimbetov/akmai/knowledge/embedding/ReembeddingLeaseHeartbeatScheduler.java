package kz.alimbetov.akmai.knowledge.embedding;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps healthy re-embedding ownership alive independently of potentially
 * blocking model calls. Fencing still prevents an expired/stale owner from
 * renewing after another replica has taken over.
 */
@Component
public class ReembeddingLeaseHeartbeatScheduler {

    private final ReembeddingLeaseManager leases;

    public ReembeddingLeaseHeartbeatScheduler(ReembeddingLeaseManager leases) {
        this.leases = leases;
    }

    @Scheduled(
            fixedDelayString = "${akmai.reembedding.heartbeat-interval:15s}"
    )
    public void heartbeat() {
        leases.renewOwnedActiveLeases();
    }
}
