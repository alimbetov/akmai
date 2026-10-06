package kz.alimbetov.akmai.rag.grounding;

import java.util.List;

public interface SemanticEntailmentClient {

    List<EntailmentStatus> evaluate(List<ClaimEvidence> claims);

    enum EntailmentStatus {
        SUPPORTED,
        CONTRADICTED,
        INSUFFICIENT
    }

    record ClaimEvidence(
            String claim,
            List<String> evidence
    ) {
        public ClaimEvidence {
            claim = claim == null ? "" : claim.trim();
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }
}
