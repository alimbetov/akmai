package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResultFusionTest {

    private ResultFusion fusion;

    @BeforeEach
    void setUp() {
        SearchProjectionRepository repository =
                mock(SearchProjectionRepository.class);
        when(repository.findByDocumentAndChunkIds(anyString(), anyList()))
                .thenReturn(List.of());
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
        ));

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

        List<RetrievalHit> fused = fusion.fuse(List.of(q1, q2));

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
                "doc",
                "semantic",
                "semantic",
                Map.of("queryChunkId", "q1", "authorityTier", 2)
        );
        RetrievalHit exact = new RetrievalHit(
                RetrievalType.IDENTIFIER,
                "doc",
                "exact",
                "exact",
                Map.of("queryChunkId", "q1", "authorityTier", 0)
        );

        assertThat(fusion.fuse(List.of(semantic, exact)).getFirst().chunkId())
                .isEqualTo("exact");
    }

    @Test
    void fusionKeyIncludesDocumentId() {
        RetrievalHit first = new RetrievalHit(
                RetrievalType.LEXICAL,
                "doc-a",
                "same",
                "a",
                Map.of("queryChunkId", "q1")
        );
        RetrievalHit second = new RetrievalHit(
                RetrievalType.LEXICAL,
                "doc-b",
                "same",
                "b",
                Map.of("queryChunkId", "q1")
        );

        assertThat(fusion.fuse(List.of(first, second))).hasSize(2);
    }

    @Test
    void canonicalProjectionPayloadReplacesIdentifierSnippetRepresentative() {
        SearchProjectionRepository repository =
                mock(SearchProjectionRepository.class);
        SearchProjection canonical = new SearchProjection(
                "chunk-1",
                "doc",
                2L,
                null,
                4,
                "FULL CANONICAL TEXT",
                "embedding",
                "en",
                KnowledgeDomain.LEGAL,
                "Article 4",
                List.of(),
                List.of(),
                Map.of("source", "law.md"),
                2
        );
        when(repository.findByDocumentAndChunkIds(
                "doc",
                List.of("chunk-1")
        )).thenReturn(List.of(canonical));
        ResultFusion local = new ResultFusion(
                RetrievalTestProperties.defaults(),
                repository
        );
        RetrievalHit snippet = new RetrievalHit(
                RetrievalType.IDENTIFIER,
                "doc",
                "chunk-1",
                "short identifier snippet",
                Map.of("queryChunkId", "q1", "authorityTier", 0)
        );

        RetrievalHit fused = local.fuse(List.of(snippet)).getFirst();

        assertThat(fused.text()).isEqualTo("FULL CANONICAL TEXT");
        assertThat(fused.metadata())
                .containsEntry("source", "law.md")
                .containsEntry("generation", 2L);
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
                "doc",
                chunkId,
                chunkId,
                Map.of("queryChunkId", queryChunkId)
        );
    }
}
