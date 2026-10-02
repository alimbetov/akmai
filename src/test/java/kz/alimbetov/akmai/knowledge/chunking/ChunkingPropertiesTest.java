package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChunkingPropertiesTest {

    @Test
    void rejectsInvalidOrderingAtConstructionBoundary() {
        assertThatThrownBy(() -> new ChunkingProperties(10, 20, 30, 11))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkingProperties(20, 10, 30, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkingProperties(20, 30, 25, 1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> new ChunkingProperties(10, 20, 30, 5))
                .doesNotThrowAnyException();
    }
}
