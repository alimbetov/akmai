package kz.alimbetov.akmai.knowledge.ingestion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository.VectorGenerationEntry;
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
        return Map.copyOf(metadata);
    }

    public record Assembly(
            List<VectorGenerationEntry> manifest,
            List<VectorRow> vectors
    ) {
    }
}
