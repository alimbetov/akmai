package kz.alimbetov.akmai.rag.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.config.SelfOptimizingRagProperties;
import kz.alimbetov.akmai.rag.retrieval.AnswerGroundingVerifier;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class SemanticGroundingVerifierTest {

    @Test
    void rejectsSemanticContradictionAfterDeterministicGroundingPasses() {
        SemanticEntailmentClient client = claims -> List.of(
                SemanticEntailmentClient.EntailmentStatus.CONTRADICTED
        );
        SemanticGroundingVerifier verifier = new SemanticGroundingVerifier(
                properties(true),
                client
        );
        var deterministic = new AnswerGroundingVerifier.GroundingValidation(
                true,
                0,
                0,
                List.of(new AnswerGroundingVerifier.ClaimValidation(
                        "The medicine is allowed for condition X.",
                        List.of(1),
                        AnswerGroundingVerifier.ClaimStatus.SUPPORTED
                ))
        );
        RetrievalHit hit = new RetrievalHit(
                RetrievalType.LEXICAL,
                "doc-1",
                "chunk-1",
                "The medicine is contraindicated for condition X.",
                Map.of("language", "en", "generation", 1L)
        );

        var result = verifier.verify(deterministic, List.of(hit));

        assertThat(result.status())
                .isEqualTo(SemanticGroundingVerifier.Status.CONTRADICTED);
        assertThat(result.accepted()).isFalse();
    }

    @Test
    void modelUncertaintyFailsClosedWhenSemanticGroundingIsEnabled() {
        SemanticEntailmentClient client = claims -> List.of(
                SemanticEntailmentClient.EntailmentStatus.INSUFFICIENT
        );
        SemanticGroundingVerifier verifier = new SemanticGroundingVerifier(
                properties(true),
                client
        );
        var deterministic = new AnswerGroundingVerifier.GroundingValidation(
                true,
                0,
                0,
                List.of(new AnswerGroundingVerifier.ClaimValidation(
                        "A factual claim.",
                        List.of(1),
                        AnswerGroundingVerifier.ClaimStatus.SUPPORTED
                ))
        );
        RetrievalHit hit = new RetrievalHit(
                RetrievalType.LEXICAL,
                "doc-1",
                "chunk-1",
                "Evidence about another detail.",
                Map.of("language", "en", "generation", 1L)
        );

        var result = verifier.verify(deterministic, List.of(hit));

        assertThat(result.status())
                .isEqualTo(SemanticGroundingVerifier.Status.INSUFFICIENT);
        assertThat(result.accepted()).isFalse();
    }

    @Test
    void disabledSemanticGroundingAddsNoModelDependency() {
        SemanticEntailmentClient client = claims -> {
            throw new AssertionError("semantic client must not be called");
        };
        SemanticGroundingVerifier verifier = new SemanticGroundingVerifier(
                properties(false),
                client
        );

        var result = verifier.verify(null, List.of());

        assertThat(result.status())
                .isEqualTo(SemanticGroundingVerifier.Status.SKIPPED);
        assertThat(result.accepted()).isTrue();
    }

    private SelfOptimizingRagProperties properties(boolean semanticGrounding) {
        return new SelfOptimizingRagProperties(
                false,
                false,
                semanticGrounding,
                "",
                "test-corpus",
                "retrieval-test",
                "learning-test",
                "grounding-test",
                128,
                4
        );
    }
}
