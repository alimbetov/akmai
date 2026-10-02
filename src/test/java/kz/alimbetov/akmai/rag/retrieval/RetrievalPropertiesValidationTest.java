package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetrievalPropertiesValidationTest {

    @Test
    void rejectsZeroNegativeSubmillisecondAndUnboundedRerankerTimeouts() {
        assertThatThrownBy(() -> properties(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reranker-timeout");
        assertThatThrownBy(() -> properties(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reranker-timeout");
        assertThatThrownBy(() -> properties(Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reranker-timeout");
        assertThatThrownBy(() -> properties(Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reranker-timeout");
    }

    private RetrievalProperties properties(Duration rerankerTimeout) {
        RetrievalProperties defaults = RetrievalTestProperties.defaults();
        return new RetrievalProperties(
                defaults.parallelism(),
                defaults.queueCapacity(),
                defaults.vectorTopK(),
                defaults.vectorSimilarityThreshold(),
                defaults.lexicalLimit(),
                defaults.identifierLimit(),
                defaults.referenceLimit(),
                defaults.rrfK(),
                defaults.expansionSeeds(),
                defaults.expansionRadius(),
                defaults.expansionMax(),
                defaults.contextMaxTokens(),
                defaults.contextMaxChunks(),
                defaults.contextMaxChunksPerDocument(),
                true,
                defaults.rerankerCandidates(),
                rerankerTimeout,
                defaults.rerankerFusedWeight(),
                defaults.requestTimeout(),
                defaults.strategyTimeout(),
                defaults.answerTimeout(),
                defaults.embeddingHttpTimeout(),
                defaults.contextExpansionMaxChunks(),
                defaults.answerReservedTokens()
        );
    }
}
