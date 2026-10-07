package kz.alimbetov.akmai.rag.policy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class CanaryRoutingObservationStore {

    private final Cache<String, Decision> decisions = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    public void record(String requestId, Decision decision) {
        if (requestId == null
                || requestId.isBlank()
                || decision == null
                || decision.policyVersion() == null
                || decision.policyVersion().isBlank()) {
            return;
        }
        decisions.put(requestId, decision);
    }

    public Optional<Decision> consume(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        Decision decision = decisions.getIfPresent(requestId);
        if (decision != null) {
            decisions.invalidate(requestId);
        }
        return Optional.ofNullable(decision);
    }

    public enum Cohort {
        CANARY,
        CONTROL
    }

    public record Decision(
            String policyVersion,
            Cohort cohort,
            String queryClass,
            boolean candidateWouldChangePlan,
            Instant selectedAt
    ) {
        public Decision {
            queryClass = queryClass == null || queryClass.isBlank()
                    ? "ANALYSIS_UNAVAILABLE"
                    : queryClass;
            selectedAt = selectedAt == null ? Instant.now() : selectedAt;
        }
    }
}
