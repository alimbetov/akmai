package kz.alimbetov.akmai.rag.assurance.read;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
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
import kz.alimbetov.akmai.rag.retrieval.ResultFusion;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalType;
import org.junit.jupiter.api.Test;

class FusionContractTest {

    private static final Set<Long> ACCESS = Set.of(1L);

    @Test
    void disallowedAccessLevelCannotSurviveFusion() {
        PublishedSearchProjectionReader repository = canonicalRepository();
        ResultFusion fusion = new ResultFusion(properties(), repository);
        RetrievalHit allowed = hit(RetrievalType.VECTOR, 1L, 2L, "allowed");
        RetrievalHit restricted = hit(RetrievalType.VECTOR, 99L, 2L, "restricted");

        var fused = fusion.fuse(List.of(restricted, allowed), ACCESS);

        assertDoesNotThrow(() -> RagAssertions.security(fused)
                .forFixture("r02-fusion-acl")
                .hasNoAclLeak(ACCESS));
        assertDoesNotThrow(() -> RagAssertions.retrieval(fused)
                .forFixture("r02-fusion-acl")
                .containsChunkIds("allowed")
                .excludesChunkIds("restricted"));
    }

    @Test
    void staleGenerationIsDroppedByPublishedCanonicalization() {
        PublishedSearchProjectionReader repository = mock(PublishedSearchProjectionReader.class);
        when(repository.findPublishedByKeys(anyList(), anySet()))
                .thenReturn(List.of(projection(1L, 2L, "fresh", "fresh canonical")));
        ResultFusion fusion = new ResultFusion(properties(), repository);

        RetrievalHit stale = hit(RetrievalType.VECTOR, 1L, 1L, "stale");
        RetrievalHit fresh = hit(RetrievalType.VECTOR, 1L, 2L, "fresh");

        var fused = fusion.fuse(List.of(stale, fresh), ACCESS);

        assertDoesNotThrow(() -> RagAssertions.retrieval(fused)
                .forFixture("r03-published-generation")
                .containsChunkIds("fresh")
                .excludesChunkIds("stale")
                .hasRoutingIdentity());
        assertDoesNotThrow(() -> RagAssertions.lifecycle(fused)
                .forFixture("r03-published-generation")
                .containsPublishedGenerationsOnly(hit -> hit.generation() == 2L));
    }

    @Test
    void duplicateLaneHitsCollapseToOneCanonicalHitWithAllEvidence() {
        PublishedSearchProjectionReader repository = canonicalRepository();
        ResultFusion fusion = new ResultFusion(properties(), repository);

        RetrievalHit vector = hit(RetrievalType.VECTOR, 1L, 2L, "shared");
        RetrievalHit lexical = hit(RetrievalType.LEXICAL, 1L, 2L, "shared");

        var fused = fusion.fuse(List.of(vector, lexical), ACCESS);

        assertEquals(1, fused.size());
        assertDoesNotThrow(() -> RagAssertions.retrieval(fused)
                .forFixture("r04-canonical-fusion")
                .hasNoDuplicateCanonicalHits()
                .hasRetrievalEvidence()
                .hasEvidenceTypes(
                        "shared",
                        RetrievalType.VECTOR,
                        RetrievalType.LEXICAL
                ));
    }

    @Test
    void exactIdentifierAuthoritySurvivesSemanticRepresentativeOrdering() {
        PublishedSearchProjectionReader repository = canonicalRepository();
        ResultFusion fusion = new ResultFusion(properties(), repository);

        RetrievalHit semanticFirst = hit(RetrievalType.VECTOR, 1L, 2L, "authoritative");
        RetrievalHit exactSecond = hit(RetrievalType.IDENTIFIER, 1L, 2L, "authoritative");

        var fused = fusion.fuse(List.of(semanticFirst, exactSecond), ACCESS);

        assertEquals(1, fused.size());
        assertEquals("canonical authoritative", fused.getFirst().text());
        assertDoesNotThrow(() -> RagAssertions.retrieval(fused)
                .forFixture("r05-exact-authority")
                .hasAuthorityTier("authoritative", 0)
                .hasEvidenceTypes(
                        "authoritative",
                        RetrievalType.VECTOR,
                        RetrievalType.IDENTIFIER
                ));
    }

    private PublishedSearchProjectionReader canonicalRepository() {
        PublishedSearchProjectionReader repository = mock(PublishedSearchProjectionReader.class);
        when(repository.findPublishedByKeys(anyList(), anySet()))
                .thenAnswer(invocation -> {
                    List<PublishedSearchProjectionReader.ProjectionKey> keys =
                            invocation.getArgument(0);
                    return keys.stream()
                            .map(key -> projection(
                                    key.accessLevel(),
                                    key.generation(),
                                    key.chunkId(),
                                    "canonical " + key.chunkId()
                            ))
                            .toList();
                });
        return repository;
    }

    private RetrievalHit hit(
            RetrievalType type,
            long accessLevel,
            long generation,
            String chunkId
    ) {
        return new RetrievalHit(
                type,
                accessLevel,
                "doc",
                generation,
                chunkId,
                "candidate " + chunkId,
                Map.of("queryChunkId", "q1", "score", 0.9)
        );
    }

    private SearchProjection projection(
            long accessLevel,
            long generation,
            String chunkId,
            String text
    ) {
        return new SearchProjection(
                chunkId,
                "doc",
                generation,
                accessLevel,
                null,
                0,
                text,
                "embedding " + chunkId,
                "en",
                KnowledgeDomain.LEGAL,
                "Article 25",
                List.of(),
                List.of(),
                Map.of("source", "fusion-contract.md"),
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
                3,
                1,
                3,
                2048,
                8,
                4,
                true,
                8,
                Duration.ofMillis(100),
                0.5
        );
    }
}
