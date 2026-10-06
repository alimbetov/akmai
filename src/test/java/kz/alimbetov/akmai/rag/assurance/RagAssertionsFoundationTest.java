package kz.alimbetov.akmai.rag.assurance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.rag.retrieval.AnswerGroundingVerifier;
import kz.alimbetov.akmai.rag.retrieval.CitationValidator;
import kz.alimbetov.akmai.rag.retrieval.RetrievalEvidence;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class RagAssertionsFoundationTest {

    private final TokenEstimator tokenEstimator = new TokenEstimator();

    @Test
    void facadeExposesReusableTypedAssertions() {
        KnowledgeChunk chunk = chunk("chunk-1", "doc-1", "Canonical evidence");
        RetrievalHit hit = hit("chunk-1", 10L, 3L);
        CitationValidator.CitationValidation citation = new CitationValidator()
                .validate("Supported claim [SOURCE 1]", List.of(hit));
        AnswerGroundingVerifier.GroundingValidation grounding =
                new AnswerGroundingVerifier().verify(
                        "Supported claim [SOURCE 1]",
                        List.of(hit),
                        "en"
                );

        assertDoesNotThrow(() -> RagAssertions.chunks(List.of(chunk))
                .forFixture("foundation-positive")
                .containsNoEmptyChunks()
                .preservesDocumentIdentity("doc-1")
                .hasUniqueChunkIds()
                .hasChunkIds(Set.of("chunk-1"))
                .respectsHardTokenLimit(tokenEstimator, 100));

        assertDoesNotThrow(() -> RagAssertions.retrieval(List.of(hit))
                .forFixture("foundation-positive")
                .hasNoDuplicateCanonicalHits()
                .hasRetrievalEvidence()
                .hasRoutingIdentity());

        assertDoesNotThrow(() -> RagAssertions.context(List.of(hit))
                .forFixture("foundation-positive")
                .containsNoBlankEvidence()
                .isWithinTokenBudget(tokenEstimator, 100));

        assertDoesNotThrow(() -> RagAssertions.citation(citation)
                .forFixture("foundation-positive")
                .hasNoInvalidSourceNumbers()
                .hasAtLeastOneCitedSource());

        assertDoesNotThrow(() -> RagAssertions.grounding(grounding)
                .forFixture("foundation-positive")
                .isGrounded()
                .isGroundedOrAbstains(false));

        assertDoesNotThrow(() -> RagAssertions.lifecycle(List.of(hit))
                .forFixture("foundation-positive")
                .hasPositiveGenerationIdentity()
                .containsPublishedGenerationsOnly(candidate -> candidate.generation() == 3L));

        assertDoesNotThrow(() -> RagAssertions.security(List.of(hit))
                .forFixture("foundation-positive")
                .hasNoAclLeak(Set.of(10L)));
    }

    @Test
    void failureMessagesCarryContractFixtureAndViolatingSubject() {
        RetrievalHit restricted = hit("restricted-chunk", 99L, 3L);

        AssertionError error = assertThrows(
                AssertionError.class,
                () -> RagAssertions.security(List.of(restricted))
                        .forFixture("security-fixture-01")
                        .hasNoAclLeak(Set.of(10L))
        );

        assertTrue(error.getMessage().contains("[RAG-CONTRACT R-02]"));
        assertTrue(error.getMessage().contains("fixture=security-fixture-01"));
        assertTrue(error.getMessage().contains("violating=restricted-chunk"));
        assertTrue(error.getMessage().contains("invariant="));
    }

    @Test
    void groundingAssertionAcceptsExplicitAbstention() {
        AnswerGroundingVerifier.GroundingValidation rejected =
                new AnswerGroundingVerifier.GroundingValidation(
                        false,
                        1,
                        0,
                        List.of()
                );

        assertDoesNotThrow(() -> RagAssertions.grounding(rejected)
                .forFixture("abstention-fixture")
                .isGroundedOrAbstains(true));
    }

    private KnowledgeChunk chunk(String chunkId, String documentId, String text) {
        return new KnowledgeChunk(
                chunkId,
                documentId,
                null,
                0,
                text,
                text,
                text,
                "Title",
                "Section",
                "en",
                KnowledgeDomain.TECHNICAL,
                List.of(),
                Map.of("language", "en")
        );
    }

    private RetrievalHit hit(String chunkId, long accessLevel, long generation) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                accessLevel,
                "doc-1",
                generation,
                chunkId,
                "Supported claim",
                Map.of("language", "en"),
                List.of(new RetrievalEvidence(RetrievalType.VECTOR, 1, 0.9)),
                1.0
        );
    }
}
