package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.model.ChunkRole;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.junit.jupiter.api.Test;

class GenerationVectorAssemblerParentChildTest {

    @Test
    void excludesParentsFromManifestAndVectorsDuringReembedding() {
        GenerationVectorAssembler assembler = new GenerationVectorAssembler();
        SearchProjection parent = projection(
                "parent-1",
                null,
                ChunkRole.PARENT
        );
        SearchProjection child = projection(
                "child-1",
                "parent-1",
                ChunkRole.CHILD
        );
        EmbeddingProfile profile = new EmbeddingProfile(
                "profile-1",
                "ollama",
                "qwen3-embedding:4b",
                3,
                "COSINE_DISTANCE",
                "conservative-v1",
                "fingerprint",
                "public",
                "knowledge_vector",
                "HNSW",
                (short) 2,
                Instant.EPOCH
        );

        GenerationVectorAssembler.Assembly result = assembler.assemble(
                List.of(parent, child),
                List.of(
                        new float[]{1.0f, 0.0f, 0.0f},
                        new float[]{0.0f, 1.0f, 0.0f}
                ),
                profile,
                4L
        );

        assertThat(result.manifest()).hasSize(1);
        assertThat(result.manifest().getFirst().chunkId())
                .isEqualTo("child-1");
        assertThat(result.vectors()).hasSize(1);
        assertThat(result.vectors().getFirst().chunkId())
                .isEqualTo("child-1");
        assertThat(result.vectors().getFirst().metadata())
                .containsEntry(ChunkRole.METADATA_KEY, "CHILD")
                .containsEntry(ChunkRole.PARENT_CHUNK_ID_KEY, "parent-1");
    }

    private SearchProjection projection(
            String chunkId,
            String parentChunkId,
            ChunkRole role
    ) {
        return new SearchProjection(
                chunkId,
                "doc-1",
                3L,
                7L,
                parentChunkId,
                role == ChunkRole.PARENT ? 0 : 1,
                role.name() + " text",
                role.name() + " embedding text",
                "en",
                KnowledgeDomain.GENERAL,
                "Section 1",
                List.of(),
                List.of(),
                Map.of(ChunkRole.METADATA_KEY, role.name()),
                2
        );
    }
}
