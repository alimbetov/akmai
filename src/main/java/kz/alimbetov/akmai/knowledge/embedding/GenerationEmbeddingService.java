package kz.alimbetov.akmai.knowledge.embedding;

import java.util.ArrayList;
import java.util.List;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

@Service
public class GenerationEmbeddingService {

    private final EmbeddingModel embeddingModel;

    public GenerationEmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public List<float[]> embed(
            List<SearchProjection> projections,
            EmbeddingProfile profile
    ) {
        List<String> texts = projections.stream()
                .map(SearchProjection::embeddingText)
                .toList();
        List<float[]> result = embeddingModel.embed(texts);
        if (result.size() != projections.size()) {
            throw new IllegalStateException(
                    "Embedding result count does not match projection count"
            );
        }

        List<float[]> validated = new ArrayList<>(result.size());
        for (float[] embedding : result) {
            if (embedding == null || embedding.length != profile.dimensions()) {
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
            validated.add(copy);
        }
        return List.copyOf(validated);
    }
}
