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
        return new RetrievalHit(type, "doc", chunkId, chunkId, Map.of());
    }
}
