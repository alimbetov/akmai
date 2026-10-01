package kz.alimbetov.akmai.rag.retrieval;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingSemanticRerankScorer implements SemanticRerankScorer {

    private final EmbeddingModel embeddingModel;

    public EmbeddingSemanticRerankScorer(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public double score(String question, RetrievalHit hit) {
        float[] questionVector = embeddingModel.embed(question);
        float[] hitVector = embeddingModel.embed(hit.text());
        return cosine(questionVector, hitVector);
    }

    private double cosine(float[] left, float[] right) {
        if (left.length != right.length || left.length == 0) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
