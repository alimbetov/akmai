package kz.alimbetov.akmai.rag.grounding;

import java.util.ArrayList;
import java.util.List;
import kz.alimbetov.akmai.config.SelfOptimizingRagProperties;
import kz.alimbetov.akmai.rag.retrieval.AnswerGroundingVerifier;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import org.springframework.stereotype.Component;

@Component
public class SemanticGroundingVerifier {

    private static final int MAX_CLAIMS = 8;
    private static final int MAX_EVIDENCE_PER_CLAIM = 4;

    private final SelfOptimizingRagProperties properties;
    private final SemanticEntailmentClient entailmentClient;

    public SemanticGroundingVerifier(
            SelfOptimizingRagProperties properties,
            SemanticEntailmentClient entailmentClient
    ) {
        this.properties = properties;
        this.entailmentClient = entailmentClient;
    }

    public Verification verify(
            AnswerGroundingVerifier.GroundingValidation deterministic,
            List<RetrievalHit> context
    ) {
        if (!properties.semanticGroundingEnabled()) {
            return new Verification(Status.SKIPPED, List.of());
        }
        if (deterministic == null
                || !deterministic.grounded()
                || deterministic.claims() == null
                || deterministic.claims().isEmpty()
                || context == null
                || context.isEmpty()) {
            return new Verification(Status.INSUFFICIENT, List.of());
        }
        if (deterministic.claims().size() > MAX_CLAIMS) {
            return new Verification(Status.INSUFFICIENT, List.of());
        }

        List<SemanticEntailmentClient.ClaimEvidence> batch = new ArrayList<>();
        for (AnswerGroundingVerifier.ClaimValidation claim : deterministic.claims()) {
            if (claim.status() != AnswerGroundingVerifier.ClaimStatus.SUPPORTED
                    || claim.citations() == null
                    || claim.citations().isEmpty()) {
                return new Verification(Status.INSUFFICIENT, List.of());
            }
            List<String> evidence = new ArrayList<>();
            for (Integer citation : claim.citations()) {
                if (citation == null
                        || citation < 1
                        || citation > context.size()) {
                    continue;
                }
                RetrievalHit hit = context.get(citation - 1);
                if (hit != null && hit.text() != null && !hit.text().isBlank()) {
                    evidence.add(hit.text());
                    if (evidence.size() >= MAX_EVIDENCE_PER_CLAIM) {
                        break;
                    }
                }
            }
            if (evidence.isEmpty()) {
                return new Verification(Status.INSUFFICIENT, List.of());
            }
            batch.add(new SemanticEntailmentClient.ClaimEvidence(
                    claim.claim(),
                    List.copyOf(evidence)
            ));
        }

        List<SemanticEntailmentClient.EntailmentStatus> results =
                entailmentClient.evaluate(List.copyOf(batch));
        if (results.size() != batch.size()) {
            return new Verification(Status.INSUFFICIENT, results);
        }
        if (results.stream().anyMatch(
                status -> status == SemanticEntailmentClient.EntailmentStatus.CONTRADICTED
        )) {
            return new Verification(Status.CONTRADICTED, results);
        }
        if (results.stream().anyMatch(
                status -> status != SemanticEntailmentClient.EntailmentStatus.SUPPORTED
        )) {
            return new Verification(Status.INSUFFICIENT, results);
        }
        return new Verification(Status.SUPPORTED, results);
    }

    public enum Status {
        SKIPPED,
        SUPPORTED,
        CONTRADICTED,
        INSUFFICIENT
    }

    public record Verification(
            Status status,
            List<SemanticEntailmentClient.EntailmentStatus> claimStatuses
    ) {
        public Verification {
            claimStatuses = claimStatuses == null
                    ? List.of()
                    : List.copyOf(claimStatuses);
        }

        public boolean accepted() {
            return status == Status.SKIPPED || status == Status.SUPPORTED;
        }
    }
}
