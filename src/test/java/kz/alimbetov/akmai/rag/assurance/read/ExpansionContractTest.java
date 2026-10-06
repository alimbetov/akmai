package kz.alimbetov.akmai.rag.assurance.read;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.rag.assurance.RagAssertions;
import kz.alimbetov.akmai.rag.retrieval.KnowledgeExpansion;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class ExpansionContractTest {

    @Test
    void expansionPreservesAclGenerationCanonicalIdentityAndQuota() {
        PublishedSearchProjectionReader repository = mock(PublishedSearchProjectionReader.class);
        when(repository.findAdjacent(
                anyString(),
                anyLong(),
                anyInt(),
                anyInt(),
                anySet()
        )).thenAnswer(invocation -> {
            long generation = invocation.getArgument(1);
            Set<Long> accessLevels = invocation.getArgument(4);
            if (generation == 2L && accessLevels.equals(Set.of(1L))) {
                return List.of(
                        projection(1L, 2L, "seed", 5),
                        projection(1L, 2L, "neighbor", 6)
                );
            }
            return List.of(projection(99L, 1L, "restricted", 6));
        });

        KnowledgeExpansion expansion = new KnowledgeExpansion(repository, properties());
        RetrievalHit seed = new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                "doc",
                2L,
                "seed",
                "seed evidence",
                Map.of("chunkIndex", 5, "authorityTier", 2)
        );

        List<RetrievalHit> expanded = expansion.expand(List.of(seed), Set.of(1L));

        assertEquals(2, expanded.size(), "one seed plus one bounded neighbor is expected");
        assertDoesNotThrow(() -> RagAssertions.security(expanded)
                .forFixture("r07-expansion-acl")
                .hasNoAclLeak(Set.of(1L)));
        assertDoesNotThrow(() -> RagAssertions.lifecycle(expanded)
                .forFixture("r07-expansion-generation")
                .containsPublishedGenerationsOnly(hit -> hit.generation() == 2L));
        assertDoesNotThrow(() -> RagAssertions.retrieval(expanded)
                .forFixture("r07-expansion-canonical")
                .hasNoDuplicateCanonicalHits()
                .containsChunkIds("seed", "neighbor")
                .excludesChunkIds("restricted")
                .hasRoutingIdentity());
    }

    private SearchProjection projection(
            long accessLevel,
            long generation,
            String chunkId,
            int chunkIndex
    ) {
        return new SearchProjection(
                chunkId,
                "doc",
                generation,
                accessLevel,
                null,
                chunkIndex,
                "evidence " + chunkId,
                "embedding " + chunkId,
                "en",
                KnowledgeDomain.GENERAL,
                "Section",
                List.of(),
                List.of(),
                Map.of("source", "expansion-contract.md"),
                2
        );
    }

    private RetrievalProperties properties() {
        return new RetrievalProperties(
                2,
                32,
                10,
                0.0,
                10,
                10,
                10,
                60,
                1,
                1,
                1,
                2048,
                8,
                4,
                false,
                3,
                Duration.ofMillis(100),
                0.5,
                Duration.ofSeconds(8),
                Duration.ofSeconds(3),
                Duration.ofSeconds(20),
                Duration.ofSeconds(3),
                1,
                512
        );
    }
}
