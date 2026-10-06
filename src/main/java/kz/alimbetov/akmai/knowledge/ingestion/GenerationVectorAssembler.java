package kz.alimbetov.akmai.knowledge.ingestion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository.VectorGenerationEntry;
import kz.alimbetov.akmai.knowledge.model.ChunkRole;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository.VectorRow;
import org.springframework.stereotype.Component;

@Component
public class GenerationVectorAssembler {

    public Assembly assemble(
            List<SearchProjection> projections,
            List<float[]> embeddings,
            EmbeddingProfile profile,
            long generation
    ) {
        if (projections.size() != embeddings.size()) {
            throw new IllegalStateException("Embedding count mismatch");
        }

        List<VectorGenerationEntry> manifest =
                new ArrayList<>(projections.size());
        List<VectorRow> vectors = new ArrayList<>(projections.size());

        for (int index = 0; index < projections.size(); index++) {
            SearchProjection projection = projections.get(index);
            if (!ChunkRole.isSearchable(projection.metadata())) {
                continue;
            }
            String vectorId = VectorIdentity.physicalId(
                    projection.documentId(),
                    generation,
                    projection.chunkId()
            );
            manifest.add(new VectorGenerationEntry(
                    vectorId,
                    projection.chunkId()
            ));
            vectors.add(new VectorRow(
                    vectorId,
                    projection.chunkId(),
                    projection.language(),
                    projection.embeddingText(),
                    metadata(projection, profile, generation),
                    embeddings.get(index)
            ));
        }

        return new Assembly(
                List.copyOf(manifest),
                List.copyOf(vectors)
        );
    }

    private Map<String, Object> metadata(
            SearchProjection projection,
            EmbeddingProfile profile,
            long generation
    ) {
        Map<String, Object> metadata = new HashMap<>();
        projection.metadata().forEach((key, value) -> {
            if (key != null
                    && value != null
                    && !key.toLowerCase(Locale.ROOT).startsWith("akmai")) {
                metadata.put(key, value);
            }
        });
        metadata.put("akmaiMetadataVersion", 2);
        metadata.put("akmaiDocumentId", projection.documentId());
        metadata.put("akmaiGeneration", generation);
        metadata.put("akmaiEmbeddingProfileId", profile.profileId());
        metadata.put("akmaiChunkId", projection.chunkId());
        metadata.put("language", projection.language());
        metadata.put("domain", projection.domain().name());
        metadata.put(
                "sectionPath",
                projection.sectionPath() == null
                        ? ""
                        : projection.sectionPath()
        );
        metadata.put("chunkIndex", projection.chunkIndex());
        copyHierarchyMetadata(projection, metadata);
        return Map.copyOf(metadata);
    }

    private void copyHierarchyMetadata(
            SearchProjection projection,
            Map<String, Object> metadata
    ) {
        ChunkRole role = ChunkRole.fromMetadata(projection.metadata());
        if (role != null) {
            metadata.put(ChunkRole.METADATA_KEY, role.name());
        }
        if (projection.parentChunkId() != null
                && !projection.parentChunkId().isBlank()) {
            metadata.put(
                    ChunkRole.PARENT_CHUNK_ID_KEY,
                    projection.parentChunkId()
            );
        }
        copyIfPresent(
                projection.metadata(),
                metadata,
                ChunkRole.PARENT_CHUNK_INDEX_KEY
        );
        copyIfPresent(
                projection.metadata(),
                metadata,
                ChunkRole.CHILD_INDEX_KEY
        );
        copyIfPresent(
                projection.metadata(),
                metadata,
                ChunkRole.CHILD_COUNT_KEY
        );
        copyIfPresent(
                projection.metadata(),
                metadata,
                ChunkRole.ESTIMATED_TOKENS_KEY
        );
    }

    private void copyIfPresent(
            Map<String, Object> source,
            Map<String, Object> target,
            String key
    ) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    public record Assembly(
            List<VectorGenerationEntry> manifest,
            List<VectorRow> vectors
    ) {
    }
}
