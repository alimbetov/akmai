package kz.alimbetov.akmai.rag.query;

import java.util.Optional;
import kz.alimbetov.akmai.rag.policy.CanaryRoutingObservationStore;
import kz.alimbetov.akmai.rag.trace.RagRuntimeAttribution;
import org.springframework.stereotype.Component;

@Component
public class SemanticQueryMemoryNamespaceResolver {

    private static final String UNAVAILABLE = "unavailable";

    private final RagRuntimeAttribution runtimeAttribution;
    private final CanaryRoutingObservationStore canaryRoutingObservationStore;

    public SemanticQueryMemoryNamespaceResolver(
            RagRuntimeAttribution runtimeAttribution,
            CanaryRoutingObservationStore canaryRoutingObservationStore
    ) {
        this.runtimeAttribution = runtimeAttribution;
        this.canaryRoutingObservationStore = canaryRoutingObservationStore;
    }

    public Optional<SemanticQueryMemoryNamespace> resolve() {
        RagRuntimeAttribution.Snapshot snapshot = runtimeAttribution.snapshot();
        if (snapshot == null
                || UNAVAILABLE.equals(snapshot.embeddingProfileId())) {
            return Optional.empty();
        }

        String retrievalPolicyVersion = snapshot.retrievalPolicyVersion();
        var decision = canaryRoutingObservationStore.peekCurrent().orElse(null);
        if (decision != null
                && decision.cohort() == CanaryRoutingObservationStore.Cohort.CANARY) {
            retrievalPolicyVersion = decision.policyVersion();
        }

        try {
            return Optional.of(new SemanticQueryMemoryNamespace(
                    snapshot.embeddingProfileId(),
                    retrievalPolicyVersion,
                    snapshot.learningPolicyVersion(),
                    snapshot.groundingPolicyVersion()
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
