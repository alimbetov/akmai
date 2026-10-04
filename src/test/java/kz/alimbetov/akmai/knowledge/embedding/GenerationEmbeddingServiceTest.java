package kz.alimbetov.akmai.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class GenerationEmbeddingServiceTest {

    @Test
    void splitsLargeGenerationIntoConfiguredEmbeddingBatches() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        List<Integer> batchSizes = new ArrayList<>();

        when(model.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            batchSizes.add(texts.size());
            return texts.stream()
                    .map(text -> {
                        int index = Integer.parseInt(
                                text.substring("embedding-".length())
                        );
                        return new float[] {
                            index,
                            index + 1.0f,
                            index + 2.0f
                        };
                    })
                    .toList();
        });

        GenerationEmbeddingService service =
                new GenerationEmbeddingService(model, 64);

        List<SearchProjection> projections =
                java.util.stream.IntStream.range(0, 130)
                        .mapToObj(this::projection)
                        .toList();

        List<float[]> result = service.embed(
                projections,
                profile()
        );

        assertThat(batchSizes).containsExactly(64, 64, 2);
        assertThat(result).hasSize(130);
        assertThat(result.get(0))
                .containsExactly(0.0f, 1.0f, 2.0f);
        assertThat(result.get(129))
                .containsExactly(129.0f, 130.0f, 131.0f);
    }

    @Test
    void invokesHeartbeatBeforeEveryBatchAndAfterEmbedding() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream()
                    .map(text -> new float[] {1f, 2f, 3f})
                    .toList();
        });
        GenerationEmbeddingService service =
                new GenerationEmbeddingService(model, 2);
        AtomicInteger heartbeats = new AtomicInteger();

        service.embed(
                List.of(
                        projection(0),
                        projection(1),
                        projection(2),
                        projection(3),
                        projection(4)
                ),
                profile(),
                heartbeats::incrementAndGet
        );

        assertThat(heartbeats.get()).isEqualTo(4);
    }

    private SearchProjection projection(int index) {
        return new SearchProjection(
                "chunk-" + index,
                "doc-1",
                null,
                index,
                "text-" + index,
                "embedding-" + index,
                "en",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of(),
                2
        );
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                "ep-test",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "fingerprint",
                "akmai_vector",
                "test_vectors",
                "NONE",
                (short) 1,
                Instant.parse("2026-10-03T00:00:00Z")
        );
    }
}
