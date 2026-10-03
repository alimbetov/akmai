package kz.alimbetov.akmai.knowledge.embedding;

import java.util.ArrayList;
import java.util.List;
import kz.alimbetov.akmai.config.VectorStorageProperties;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class GenerationEmbeddingService {

    private static final int DEFAULT_TEST_BATCH_SIZE = 64;

    private final EmbeddingModel embeddingModel;
    private final int maxBatchSize;

    @Autowired
    public GenerationEmbeddingService(
            @Qualifier("vectorWriteEmbeddingModel")
            EmbeddingModel embeddingModel,
            VectorStorageProperties properties
    ) {
        this(embeddingModel, properties.maxDocumentBatchSize());
    }

    public GenerationEmbeddingService(EmbeddingModel embeddingModel) {
        this(embeddingModel, DEFAULT_TEST_BATCH_SIZE);
    }

    GenerationEmbeddingService(
            EmbeddingModel embeddingModel,
            int maxBatchSize
    ) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException(
                    "maxBatchSize must be positive"
            );
        }
        this.embeddingModel = embeddingModel;
        this.maxBatchSize = maxBatchSize;
    }

    public List<float[]> embed(
            List<SearchProjection> projections,
            EmbeddingProfile profile
    ) {
        if (projections == null || projections.isEmpty()) {
            return List.of();
        }

        List<float[]> validated = new ArrayList<>(projections.size());

        for (int start = 0;
                start < projections.size();
                start += maxBatchSize) {
            int end = Math.min(
                    projections.size(),
                    start + maxBatchSize
            );
            List<String> texts = projections
                    .subList(start, end)
                    .stream()
                    .map(SearchProjection::embeddingText)
                    .toList();

            List<float[]> result = embeddingModel.embed(texts);
            if (result.size() != texts.size()) {
                throw new IllegalStateException(
                        "Embedding result count does not match batch size"
                );
            }
            for (float[] embedding : result) {
                validated.add(validate(
                        embedding,
                        profile.dimensions()
                ));
            }
        }

        if (validated.size() != projections.size()) {
            throw new IllegalStateException(
                    "Embedding result count does not match projection count"
            );
        }
        return List.copyOf(validated);
    }

    private float[] validate(
            float[] embedding,
            int dimensions
    ) {
        if (embedding == null || embedding.length != dimensions) {
            throw new IllegalStateException(
                    "Embedding result has incompatible dimensions"
            );
        }
        float[] copy = embedding.clone();
        for (float value : copy) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException(
                        "Embedding result contains non-finite component"
                );
            }
        }
        return copy;
    }
}
