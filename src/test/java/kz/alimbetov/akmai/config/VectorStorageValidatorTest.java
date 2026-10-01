package kz.alimbetov.akmai.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VectorStorageValidatorTest {

    @Test
    void rejectsHnswAbovePgvectorDimensionLimit() {
        var validator = new VectorStorageValidator(
                new VectorStorageProperties(2001, "HNSW", "COSINE_DISTANCE", 64)
        );

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2000");
    }
}
