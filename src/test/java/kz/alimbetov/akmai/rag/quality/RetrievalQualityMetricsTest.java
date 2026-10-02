package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetrievalQualityMetricsTest {

    @Test
    void duplicateRelevantHitIsCreditedOnlyOnceInNdcg() {
        double value = RetrievalQualityMetrics.ndcgAtK(
                List.of("A", "A"),
                Set.of("A"),
                2
        );

        assertThat(value).isEqualTo(1.0);
    }

    @Test
    void ndcgNeverExceedsOneForBinaryRelevance() {
        double value = RetrievalQualityMetrics.ndcgAtK(
                List.of("A", "A", "B", "B"),
                Set.of("A", "B"),
                4
        );

        assertThat(value).isBetween(0.0, 1.0);
    }
}
