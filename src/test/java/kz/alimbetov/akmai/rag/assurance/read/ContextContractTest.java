package kz.alimbetov.akmai.rag.assurance.read;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.rag.assurance.RagAssertions;
import kz.alimbetov.akmai.rag.retrieval.ContextAssembler;
import kz.alimbetov.akmai.rag.retrieval.ContextBudget;
import kz.alimbetov.akmai.rag.retrieval.PublishedContextRevalidator;
import kz.alimbetov.akmai.rag.retrieval.PublishedLifecycleEligibility;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import kz.alimbetov.akmai.rag.retrieval.TemporalAuthorityFilter;
import org.junit.jupiter.api.Test;

class ContextContractTest {

    @Test
    void inactiveTemporalEvidenceCannotEnterFinalContext() {
        TemporalAuthorityFilter filter = new TemporalAuthorityFilter();
        RetrievalHit active = hit(
                "active",
                "Active authority",
                Map.of("documentStatus", "ACTIVE", "effectiveFrom", "2000-01-01")
        );
        RetrievalHit superseded = hit(
                "superseded",
                "Old authority",
                Map.of("documentStatus", "SUPERSEDED")
        );
        RetrievalHit expired = hit(
                "expired",
                "Expired authority",
                Map.of("documentStatus", "EXPIRED")
        );
        RetrievalHit withdrawn = hit(
                "withdrawn",
                "Withdrawn authority",
                Map.of("documentStatus", "WITHDRAWN")
        );
        RetrievalHit future = hit(
                "future",
                "Future authority",
                Map.of("documentStatus", "ACTIVE", "effectiveFrom", "2999-01-01")
        );

        List<RetrievalHit> filtered = filter.filter(List.of(
                active,
                superseded,
                expired,
                withdrawn,
                future
        ));

        assertDoesNotThrow(() -> RagAssertions.context(filtered)
                .forFixture("r08-temporal-authority")
                .excludesTemporalEvidence(
                        "superseded",
                        "expired",
                        "withdrawn",
                        "future"
                ));
        assertFalse(filtered.isEmpty(), "active authority must remain eligible");
    }

    @Test
    void finalSerializedContextRespectsConfiguredBudgetIncludingEnvelopeOverhead() {
        TokenEstimator estimator = new TokenEstimator();
        ContextAssembler assembler = new ContextAssembler(new ObjectMapper());
        RetrievalProperties properties = properties(256);
        ContextBudget budget = new ContextBudget(estimator, properties, assembler);
        String largeEvidence = ("bounded evidence with attribution metadata ").repeat(10);
        List<RetrievalHit> candidates = List.of(
                hit("budget-1", largeEvidence, Map.of()),
                hit("budget-2", largeEvidence, Map.of()),
                hit("budget-3", largeEvidence, Map.of())
        );

        List<RetrievalHit> bounded = budget.apply(candidates, "budget question");

        assertFalse(bounded.isEmpty(), "fixture must retain at least one context item");
        assertDoesNotThrow(() -> RagAssertions.context(bounded)
                .forFixture("r09-serialized-context-budget")
                .containsNoBlankEvidence()
                .isWithinSerializedTokenBudget(estimator, assembler, 256));
    }

    @Test
    void evidenceThatIsNoLongerPublishedIsRemovedAtFinalRevalidation() {
        PublishedSearchProjectionReader reader = mock(PublishedSearchProjectionReader.class);
        when(reader.findPublishedByKeys(anyList(), anySet()))
                .thenReturn(List.of(projection("fresh", 2L)));
        PublishedContextRevalidator revalidator = new PublishedContextRevalidator(
                reader,
                PublishedLifecycleEligibility.allowAll()
        );
        RetrievalHit stale = routedHit("stale", 1L);
        RetrievalHit fresh = routedHit("fresh", 2L);

        List<RetrievalHit> revalidated = revalidator.revalidate(
                List.of(stale, fresh),
                Set.of(1L)
        );

        assertDoesNotThrow(() -> RagAssertions.context(revalidated)
                .forFixture("r10-published-revalidation")
                .containsOnlyRevalidatedChunkIds("fresh"));
        assertDoesNotThrow(() -> RagAssertions.security(revalidated)
                .forFixture("r10-published-revalidation")
                .hasNoAclLeak(Set.of(1L)));
    }

    private RetrievalHit hit(
            String chunkId,
            String text,
            Map<String, Object> extraMetadata
    ) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("source", "context-contract.md");
        metadata.put("language", "en");
        metadata.put("sectionPath", "Section");
        metadata.putAll(extraMetadata);
        return new RetrievalHit(
                RetrievalType.LEXICAL,
                1L,
                "doc",
                2L,
                chunkId,
                text,
                metadata
        );
    }

    private RetrievalHit routedHit(String chunkId, long generation) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                "doc",
                generation,
                chunkId,
                "evidence " + chunkId,
                Map.of(
                        "source", "context-contract.md",
                        "language", "en",
                        "sectionPath", "Section"
                )
        );
    }

    private SearchProjection projection(String chunkId, long generation) {
        return new SearchProjection(
                chunkId,
                "doc",
                generation,
                1L,
                null,
                0,
                "evidence " + chunkId,
                "embedding " + chunkId,
                "en",
                KnowledgeDomain.GENERAL,
                "Section",
                List.of(),
                List.of(),
                Map.of("source", "context-contract.md"),
                2
        );
    }

    private RetrievalProperties properties(int contextMaxTokens) {
        return new RetrievalProperties(
                2,
                32,
                10,
                0.0,
                10,
                10,
                10,
                60,
                2,
                1,
                2,
                contextMaxTokens,
                8,
                8,
                false,
                3,
                Duration.ofMillis(100),
                0.5,
                Duration.ofSeconds(8),
                Duration.ofSeconds(3),
                Duration.ofSeconds(20),
                Duration.ofSeconds(3),
                2,
                512
        );
    }
}
