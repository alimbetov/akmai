package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResultFusionTest {

    private final ResultFusion fusion = new ResultFusion();

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
    void toleratesMissingChunkId() {
        RetrievalHit hit = new RetrievalHit(
                RetrievalType.IDENTIFIER,
                "doc",
                null,
                "context",
                Map.of()
        );

        assertThat(fusion.fuse(List.of(hit))).hasSize(1);
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
