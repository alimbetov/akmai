package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticMorphologyRegistryTest {

    @Test
    void registersLanguageSpecificMorphologyWithoutUniversalHeuristics() {
        SemanticMorphologyRegistry registry =
                new SemanticMorphologyRegistry(
                        List.of(
                                new EnglishSemanticMorphologyNormalizer()
                        )
                );

        assertThat(registry.supports("en")).isTrue();
        assertThat(registry.supports("ru")).isFalse();
        assertThat(registry.require("en").language()).isEqualTo("en");

        assertThatThrownBy(() -> registry.require("ru"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No semantic morphology normalizer");
    }
}
