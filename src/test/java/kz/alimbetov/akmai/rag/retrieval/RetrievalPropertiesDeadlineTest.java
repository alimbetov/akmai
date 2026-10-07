package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetrievalPropertiesDeadlineTest {

    @Test
    void strategyTimeoutMustNotExceedRequestTimeout() {
        RetrievalProperties defaults = RetrievalTestProperties.defaults();

        assertThatThrownBy(() -> new RetrievalProperties(
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
                defaults.rerankerEnabled(),
                defaults.rerankerCandidates(),
                defaults.rerankerTimeout(),
                defaults.rerankerFusedWeight(),
                Duration.ofMillis(100),
                Duration.ofMillis(101),
                defaults.answerTimeout(),
                defaults.embeddingHttpTimeout(),
                defaults.contextExpansionMaxChunks(),
                defaults.answerReservedTokens()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "strategy-timeout must be <= request-timeout"
                );
    }
}
