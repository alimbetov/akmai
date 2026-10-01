package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingSemanticRerankScorer implements SemanticRerankScorer {

    private final EmbeddingModel embeddingModel;

    public EmbeddingSemanticRerankScorer(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<Double> score(String question, List<RetrievalHit> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }

        List<String> inputs = new ArrayList<>(hits.size() + 1);
        inputs.add(question);
        hits.stream().map(RetrievalHit::text).forEach(inputs::add);

        List<float[]> embeddings = embeddingModel.embed(inputs);
        if (embeddings.size() != inputs.size()) {
            throw new IllegalStateException(
                    "Embedding model returned unexpected vector count"
            );
        }

        float[] questionVector = requireVector(embeddings.getFirst(), -1);
        List<Double> scores = new ArrayList<>(hits.size());
        for (int i = 1; i < embeddings.size(); i++) {
            float[] candidate = requireVector(embeddings.get(i), questionVector.length);
            double score = cosine(questionVector, candidate);
            if (!Double.isFinite(score)) {
                throw new IllegalStateException("Rerank score is not finite");
            }
            scores.add(score);
        }
        return List.copyOf(scores);
    }

    private float[] requireVector(float[] value, int expectedDimensions) {
        if (value == null || value.length == 0) {
            throw new IllegalStateException("Embedding vector is empty");
        }
        if (expectedDimensions > 0 && value.length != expectedDimensions) {
            throw new IllegalStateException("Embedding dimensions do not match");
        }
        for (float component : value) {
            if (!Float.isFinite(component)) {
                throw new IllegalStateException(
                        "Embedding vector contains a non-finite component"
                );
            }
        }
        return value;
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (!Double.isFinite(dot)
                || !Double.isFinite(leftNorm)
                || !Double.isFinite(rightNorm)
                || leftNorm == 0.0
                || rightNorm == 0.0) {
            throw new IllegalStateException("Embedding vectors cannot be scored");
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
