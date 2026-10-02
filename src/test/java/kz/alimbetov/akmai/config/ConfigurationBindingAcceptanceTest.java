package kz.alimbetov.akmai.config;

import static org.assertj.core.api.Assertions.assertThat;

import kz.alimbetov.akmai.knowledge.chunking.ChunkingProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ConfigurationBindingAcceptanceTest {

    private static final String[] RETRIEVAL_DEFAULTS = {
        "akmai.retrieval.parallelism=4",
        "akmai.retrieval.queue-capacity=32",
        "akmai.retrieval.vector-top-k=10",
        "akmai.retrieval.vector-similarity-threshold=0.5",
        "akmai.retrieval.lexical-limit=10",
        "akmai.retrieval.identifier-limit=10",
        "akmai.retrieval.reference-limit=10",
        "akmai.retrieval.rrf-k=60",
        "akmai.retrieval.expansion-seeds=3",
        "akmai.retrieval.expansion-radius=1",
        "akmai.retrieval.expansion-max=3",
        "akmai.retrieval.context-max-tokens=1000",
        "akmai.retrieval.context-max-chunks=10",
        "akmai.retrieval.context-max-chunks-per-document=4",
        "akmai.retrieval.reranker-enabled=true",
        "akmai.retrieval.reranker-candidates=10",
        "akmai.retrieval.reranker-timeout=500ms",
        "akmai.retrieval.reranker-fused-weight=0.2",
        "akmai.retrieval.request-timeout=5s",
        "akmai.retrieval.strategy-timeout=2s",
        "akmai.retrieval.answer-timeout=10s",
        "akmai.retrieval.embedding-http-timeout=2s",
        "akmai.retrieval.context-expansion-max-chunks=2",
        "akmai.retrieval.answer-reserved-tokens=512"
    };

    @Test
    void validChunkingOverridesBindAtStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(ChunkingConfig.class)
                .withPropertyValues(
                        "akmai.chunking.min-tokens=50",
                        "akmai.chunking.target-tokens=100",
                        "akmai.chunking.soft-max-tokens=150",
                        "akmai.chunking.hard-max-tokens=200"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ChunkingProperties properties =
                            context.getBean(ChunkingProperties.class);
                    assertThat(properties.minTokens()).isEqualTo(50);
                    assertThat(properties.hardMaxTokens()).isEqualTo(200);
                });
    }

    @Test
    void invalidChunkingOrderingFailsApplicationStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(ChunkingConfig.class)
                .withPropertyValues(
                        "akmai.chunking.min-tokens=50",
                        "akmai.chunking.target-tokens=180",
                        "akmai.chunking.soft-max-tokens=120",
                        "akmai.chunking.hard-max-tokens=200"
                )
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull());
    }

    @Test
    void validRetrievalTimeoutsBindAtStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(RetrievalConfig.class)
                .withPropertyValues(RETRIEVAL_DEFAULTS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RetrievalProperties.class)
                            .rerankerTimeout())
                            .isEqualTo(java.time.Duration.ofMillis(500));
                });
    }

    @Test
    void subMillisecondRerankerTimeoutFailsApplicationStartup() {
        String[] properties = RETRIEVAL_DEFAULTS.clone();
        for (int index = 0; index < properties.length; index++) {
            if (properties[index].startsWith(
                    "akmai.retrieval.reranker-timeout="
            )) {
                properties[index] =
                        "akmai.retrieval.reranker-timeout=1ns";
            }
        }

        new ApplicationContextRunner()
                .withUserConfiguration(RetrievalConfig.class)
                .withPropertyValues(properties)
                .run(context -> assertThat(context.getStartupFailure())
                        .isNotNull());
    }


    @Test
    void apiPropertiesWithRequestCeilingBindAtStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(ApiConfig.class)
                .withPropertyValues(
                        "akmai.api.max-request-bytes=2097152",
                        "akmai.api.max-document-chars=1000000",
                        "akmai.api.max-question-chars=20000",
                        "akmai.api.max-metadata-bytes=65536",
                        "akmai.api.max-metadata-entries=256",
                        "akmai.api.max-metadata-depth=8",
                        "akmai.api.max-title-chars=1000",
                        "akmai.api.max-source-chars=1000"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ApiProperties.class)
                            .maxRequestBytes()).isEqualTo(2_097_152);
                });
    }

    @Test
    void vectorPropertiesWithOperationalTimeoutsBindAtStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(VectorConfig.class)
                .withPropertyValues(
                        "akmai.vector.dimensions=1024",
                        "akmai.vector.index-type=HNSW",
                        "akmai.vector.distance-type=COSINE_DISTANCE",
                        "akmai.vector.max-document-batch-size=64",
                        "akmai.vector.embedding-http-timeout=30s",
                        "akmai.vector.db-transaction-timeout=30s",
                        "akmai.vector.tokenizer-profile=conservative-v1"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(VectorStorageProperties.class)
                            .dimensions()).isEqualTo(1024);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChunkingProperties.class)
    static class ChunkingConfig {
    }


    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ApiProperties.class)
    static class ApiConfig {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VectorStorageProperties.class)
    static class VectorConfig {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RetrievalProperties.class)
    static class RetrievalConfig {
    }
}
