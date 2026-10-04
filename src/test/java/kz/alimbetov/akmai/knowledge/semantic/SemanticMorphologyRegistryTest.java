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
                                new EnglishSemanticMorphologyNormalizer(),
                                new RussianSemanticMorphologyNormalizer(),
                                new KazakhSemanticMorphologyNormalizer()
                        )
                );

        assertThat(registry.supports("en")).isTrue();
        assertThat(registry.supports("ru")).isTrue();
        assertThat(registry.supports("kk")).isTrue();
        assertThat(registry.supports("zh")).isFalse();
        assertThat(registry.require("en").language()).isEqualTo("en");
        assertThat(registry.require("ru").language()).isEqualTo("ru");
        assertThat(registry.require("kk").language()).isEqualTo("kk");

        assertThatThrownBy(() -> registry.require("zh"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No semantic morphology normalizer");
    }
}
