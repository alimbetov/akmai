package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResultFusionTest {

    private static final Set<Long> ACCESS = Set.of(1L);

    private ResultFusion fusion;

    @BeforeEach
    void setUp() {
        PublishedSearchProjectionReader repository =
                mock(PublishedSearchProjectionReader.class);
        when(repository.findPublishedByKeys(
                anyList(),
                anySet()
        )).thenAnswer(invocation -> {
            List<PublishedSearchProjectionReader.ProjectionKey> keys =
                    invocation.getArgument(0);
            return keys.stream()
                    .map(key -> projection(
                            key.accessLevel(),
                            key.documentId(),
                            key.generation(),
                            key.chunkId(),
                            key.chunkId()
                    ))
                    .toList();
        });
        fusion = new ResultFusion(
                RetrievalTestProperties.defaults(),
                repository
        );
    }

    @Test
    void rewardsEvidenceFromMultipleRetrievalChannels() {
        RetrievalHit lexicalShared = hit(RetrievalType.LEXICAL, "shared");
        RetrievalHit lexicalOnly = hit(RetrievalType.LEXICAL, "lexical-only");
        RetrievalHit vectorShared = hit(RetrievalType.VECTOR, "shared");

        List<RetrievalHit> fused = fusion.fuse(List.of(
                lexicalShared,
                lexicalOnly,
                vectorShared
        ), ACCESS);

        assertThat(fused).hasSize(2);
        assertThat(fused.getFirst().chunkId()).isEqualTo("shared");
        assertThat(fused.getFirst().evidence()).hasSize(2);
        assertThat(fused.getFirst().fusedScore())
                .isGreaterThan(fused.get(1).fusedScore());
    }

    @Test
    void ranksEachQueryChunkIndependently() {
        RetrievalHit q1 = hit(RetrievalType.LEXICAL, "q1-first", "q1");
        RetrievalHit q2 = hit(RetrievalType.LEXICAL, "q2-first", "q2");

        List<RetrievalHit> fused = fusion.fuse(List.of(q1, q2), ACCESS);

        assertThat(fused.get(0).fusedScore())
                .isEqualTo(fused.get(1).fusedScore());
        assertThat(fused)
                .allSatisfy(hit -> assertThat(hit.evidence().getFirst().rank())
                        .isEqualTo(1));
    }

    @Test
    void exactAuthorityTierSortsAheadOfSemanticEvidence() {
        RetrievalHit semantic = new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                "doc",
                1L,
                "semantic",
                "semantic",
                Map.of(
                        "queryChunkId", "q1",
                        "authorityTier", 0
                )
        );
        RetrievalHit exact = new RetrievalHit(
                RetrievalType.IDENTIFIER,
                1L,
                "doc",
                1L,
                "exact",
                "exact",
                Map.of("queryChunkId", "q1")
        );

        assertThat(fusion.fuse(List.of(semantic, exact), ACCESS)
                .getFirst().chunkId())
                .isEqualTo("exact");
    }

    @Test
    void fusionKeyIncludesDocumentId() {
        RetrievalHit first = new RetrievalHit(
                RetrievalType.LEXICAL,
                1L,
                "doc-a",
                1L,
                "same",
                "a",
                Map.of("queryChunkId", "q1")
        );
        RetrievalHit second = new RetrievalHit(
                RetrievalType.LEXICAL,
                1L,
                "doc-b",
                1L,
                "same",
                "b",
                Map.of("queryChunkId", "q1")
        );

        assertThat(fusion.fuse(List.of(first, second), ACCESS)).hasSize(2);
    }

    @Test
    void canonicalProjectionPayloadReplacesIdentifierSnippetRepresentative() {
        PublishedSearchProjectionReader repository =
                mock(PublishedSearchProjectionReader.class);
        SearchProjection canonical = projection(
                "doc",
                2L,
                "chunk-1",
                "FULL CANONICAL TEXT"
        );
        when(repository.findPublishedByKeys(
                List.of(new PublishedSearchProjectionReader.ProjectionKey(
                        1L,
                        "doc",
                        2L,
                        "chunk-1"
                )),
                ACCESS
        )).thenReturn(List.of(canonical));
        ResultFusion local = new ResultFusion(
                RetrievalTestProperties.defaults(),
                repository
        );
        RetrievalHit snippet = new RetrievalHit(
                RetrievalType.IDENTIFIER,
                1L,
                "doc",
                2L,
                "chunk-1",
                "short identifier snippet",
                Map.of("queryChunkId", "q1")
        );

        RetrievalHit fused = local.fuse(List.of(snippet), ACCESS).getFirst();

        assertThat(fused.text()).isEqualTo("FULL CANONICAL TEXT");
        assertThat(fused.metadata())
                .containsEntry("source", "law.md")
                .containsEntry("generation", 2L);
    }

    @Test
    void staleGenerationIsDroppedInsteadOfReadingNewPublication() {
        PublishedSearchProjectionReader repository =
                mock(PublishedSearchProjectionReader.class);
        when(repository.findPublishedByKeys(
                List.of(new PublishedSearchProjectionReader.ProjectionKey(
                        1L,
                        "doc",
                        1L,
                        "chunk-1"
                )),
                ACCESS
        )).thenReturn(List.of());

        ResultFusion local = new ResultFusion(
                RetrievalTestProperties.defaults(),
                repository
        );
        RetrievalHit stale = new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                "doc",
                1L,
                "chunk-1",
                "generation one text",
                Map.of("queryChunkId", "q1")
        );

        assertThat(local.fuse(List.of(stale), ACCESS)).isEmpty();
    }

    @Test
    void userAuthorityMetadataCannotElevateSemanticHit() {
        RetrievalHit injected = new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                "doc",
                1L,
                "semantic",
                "semantic",
                Map.of(
                        "queryChunkId", "q1",
                        "authorityTier", 0,
                        "authority", "EXACT_REFERENCE"
                )
        );
        RetrievalHit normal = hit(RetrievalType.LEXICAL, "normal");

        List<RetrievalHit> fused = fusion.fuse(
                List.of(injected, normal),
                ACCESS
        );

        assertThat(fused)
                .allSatisfy(value ->
                        assertThat(value.metadata().get("authorityTier"))
                                .isEqualTo(2)
                );
    }

    @Test
    void staleHitDoesNotConsumeRankOfCanonicalResult() {
        PublishedSearchProjectionReader repository =
                mock(PublishedSearchProjectionReader.class);
        when(repository.findPublishedByKeys(anyList(), anySet()))
                .thenReturn(List.of(
                        projection(
                                1L,
                                "doc",
                                2L,
                                "fresh",
                                "fresh"
                        )
                ));
        ResultFusion local = new ResultFusion(
                RetrievalTestProperties.defaults(),
                repository
        );

        RetrievalHit stale = new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                "doc",
                1L,
                "stale",
                "stale",
                Map.of("queryChunkId", "q1")
        );
        RetrievalHit fresh = new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                "doc",
                2L,
                "fresh",
                "fresh",
                Map.of("queryChunkId", "q1")
        );

        RetrievalHit fused = local.fuse(
                List.of(stale, fresh),
                ACCESS
        ).getFirst();

        assertThat(fused.evidence().getFirst().rank()).isEqualTo(1);
    }

    private RetrievalHit hit(RetrievalType type, String chunkId) {
        return hit(type, chunkId, "q1");
    }

    private RetrievalHit hit(
            RetrievalType type,
            String chunkId,
            String queryChunkId
    ) {
        return new RetrievalHit(
                type,
                1L,
                "doc",
                1L,
                chunkId,
                chunkId,
                Map.of("queryChunkId", queryChunkId)
        );
    }

    private SearchProjection projection(
            long accessLevel,
            String documentId,
            long generation,
            String chunkId,
            String text
    ) {
        return new SearchProjection(
                chunkId,
                documentId,
                generation,
                accessLevel,
                null,
                4,
                text,
                "embedding",
                "en",
                KnowledgeDomain.LEGAL,
                "Article 4",
                List.of(),
                List.of(),
                Map.of("source", "law.md"),
                2
        );
    }
}
