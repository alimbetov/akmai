package kz.alimbetov.akmai.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CanonicalEmbeddingContractTest {

    @Test
    void canonicalContractIsAccepted() {
        assertThatCode(() -> CanonicalEmbeddingContract.validate(
                CanonicalEmbeddingContract.MODEL,
                CanonicalEmbeddingContract.DIMENSIONS
        )).doesNotThrowAnyException();
    }

    @Test
    void modelDriftIsRejected() {
        assertThatThrownBy(() -> CanonicalEmbeddingContract.validate(
                "qwen3-embedding:0.6b",
                CanonicalEmbeddingContract.DIMENSIONS
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Canonical embedding model");
    }

    @Test
    void dimensionDriftIsRejected() {
        assertThatThrownBy(() -> CanonicalEmbeddingContract.validate(
                CanonicalEmbeddingContract.MODEL,
                2560
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Canonical embedding dimensions");
    }
}
