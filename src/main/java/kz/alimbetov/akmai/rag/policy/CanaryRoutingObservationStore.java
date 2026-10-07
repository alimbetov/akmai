package kz.alimbetov.akmai.rag.policy;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class CanaryRoutingObservationStore {

    private final ThreadLocal<Decision> current = new ThreadLocal<>();

    public void recordCurrent(Decision decision) {
        if (decision == null
                || decision.policyVersion() == null
                || decision.policyVersion().isBlank()) {
            current.remove();
            return;
        }
        current.set(decision);
    }

    public Optional<Decision> consumeCurrent() {
        Decision decision = current.get();
        current.remove();
        return Optional.ofNullable(decision);
    }

    public void clearCurrent() {
        current.remove();
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
