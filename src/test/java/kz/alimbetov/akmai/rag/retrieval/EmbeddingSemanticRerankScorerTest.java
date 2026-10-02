package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class EmbeddingSemanticRerankScorerTest {

    @Test
    void batchesQuestionAndCandidatesIntoSingleEmbeddingCall() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        List<String> inputs = List.of("question", "first", "second");
        when(embeddingModel.embed(inputs)).thenReturn(List.of(
                new float[]{1.0f, 0.0f},
                new float[]{1.0f, 0.0f},
                new float[]{0.0f, 1.0f}
        ));

        EmbeddingSemanticRerankScorer scorer =
                new EmbeddingSemanticRerankScorer(embeddingModel);

        List<Double> scores = scorer.score(
                "question",
                List.of(hit("first"), hit("second"))
        );

        assertThat(scores).containsExactly(1.0, 0.0);
        verify(embeddingModel).embed(inputs);
    }

    @Test
    void rejectsIncompleteEmbeddingBatch() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        List<String> inputs = List.of("question", "first", "second");
        when(embeddingModel.embed(inputs)).thenReturn(List.of(
                new float[]{1.0f, 0.0f},
                new float[]{1.0f, 0.0f}
        ));

        EmbeddingSemanticRerankScorer scorer =
                new EmbeddingSemanticRerankScorer(embeddingModel);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scorer.score(
                        "question",
                        List.of(hit("first"), hit("second"))
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected vector count");
    }

    @Test
    void rejectsNaNPositiveAndNegativeInfinityEmbeddingComponents() {
        for (float malformed : new float[] {
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY
        }) {
            EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
            List<String> inputs = List.of("question", "first");
            when(embeddingModel.embed(inputs)).thenReturn(List.of(
                    new float[]{1.0f, 0.0f},
                    new float[]{malformed, 1.0f}
            ));

            EmbeddingSemanticRerankScorer scorer =
                    new EmbeddingSemanticRerankScorer(embeddingModel);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> scorer.score(
                            "question",
                            List.of(hit("first"))
                    ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("non-finite");
        }
    }

    @Test
    void rejectsInconsistentEmbeddingDimensions() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        List<String> inputs = List.of("question", "first");
        when(embeddingModel.embed(inputs)).thenReturn(List.of(
                new float[]{1.0f, 0.0f},
                new float[]{1.0f, 0.0f, 0.0f}
        ));

        EmbeddingSemanticRerankScorer scorer =
                new EmbeddingSemanticRerankScorer(embeddingModel);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scorer.score(
                        "question",
                        List.of(hit("first"))
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimensions");
    }

    private RetrievalHit hit(String id) {
        return new RetrievalHit(
                RetrievalType.VECTOR,
                "doc",
                id,
                id,
                Map.of()
        );
    }
}
