package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResultFusionTest {

    private static final Set<Long> ACCESS = Set.of(1L);

    private ResultFusion fusion;

    @BeforeEach
    void setUp() {
        SearchProjectionRepository repository =
                mock(SearchProjectionRepository.class);
        when(repository.findByDocumentGenerationAndChunkIds(
                anyString(),
                anyLong(),
                anyList(),
                anySet()
        )).thenAnswer(invocation -> {
            String documentId = invocation.getArgument(0);
            long generation = invocation.getArgument(1);
            List<String> chunkIds = invocation.getArgument(2);
            return chunkIds.stream()
                    .map(chunkId -> projection(
                            documentId,
                            generation,
                            chunkId,
                            chunkId
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
                "doc",
                "semantic",
                "semantic",
                Map.of(
                        "queryChunkId", "q1",
                        "authorityTier", 2,
                        "generation", 1L
                )
        );
        RetrievalHit exact = new RetrievalHit(
                RetrievalType.IDENTIFIER,
                "doc",
                "exact",
                "exact",
                Map.of(
                        "queryChunkId", "q1",
                        "authorityTier", 0,
                        "generation", 1L
                )
        );

        assertThat(fusion.fuse(List.of(semantic, exact), ACCESS)
                .getFirst().chunkId())
                .isEqualTo("exact");
    }

    @Test
    void fusionKeyIncludesDocumentId() {
        RetrievalHit first = new RetrievalHit(
                RetrievalType.LEXICAL,
                "doc-a",
                "same",
                "a",
                Map.of("queryChunkId", "q1", "generation", 1L)
        );
        RetrievalHit second = new RetrievalHit(
                RetrievalType.LEXICAL,
                "doc-b",
                "same",
                "b",
                Map.of("queryChunkId", "q1", "generation", 1L)
        );

        assertThat(fusion.fuse(List.of(first, second), ACCESS)).hasSize(2);
    }

    @Test
    void canonicalProjectionPayloadReplacesIdentifierSnippetRepresentative() {
        SearchProjectionRepository repository =
                mock(SearchProjectionRepository.class);
        SearchProjection canonical = projection(
                "doc",
                2L,
                "chunk-1",
                "FULL CANONICAL TEXT"
        );
        when(repository.findByDocumentGenerationAndChunkIds(
                "doc",
                2L,
                List.of("chunk-1"),
                ACCESS
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
                Map.of(
                        "queryChunkId", "q1",
                        "authorityTier", 0,
                        "generation", 2L
                )
        );

        RetrievalHit fused = local.fuse(List.of(snippet), ACCESS).getFirst();

        assertThat(fused.text()).isEqualTo("FULL CANONICAL TEXT");
        assertThat(fused.metadata())
                .containsEntry("source", "law.md")
                .containsEntry("generation", 2L);
    }

    @Test
    void staleGenerationIsDroppedInsteadOfReadingNewPublication() {
        SearchProjectionRepository repository =
                mock(SearchProjectionRepository.class);
        when(repository.findByDocumentGenerationAndChunkIds(
                "doc",
                1L,
                List.of("chunk-1"),
                ACCESS
        )).thenReturn(List.of());

        ResultFusion local = new ResultFusion(
                RetrievalTestProperties.defaults(),
                repository
        );
        RetrievalHit stale = new RetrievalHit(
                RetrievalType.VECTOR,
                "doc",
                "chunk-1",
                "generation one text",
                Map.of("queryChunkId", "q1", "generation", 1L)
        );

        assertThat(local.fuse(List.of(stale), ACCESS)).isEmpty();
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
                Map.of(
                        "queryChunkId", queryChunkId,
                        "generation", 1L
                )
        );
    }

    private SearchProjection projection(
            String documentId,
            long generation,
            String chunkId,
            String text
    ) {
        return new SearchProjection(
                chunkId,
                documentId,
                generation,
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
