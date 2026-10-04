package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticMorphologyRegistryTest {

    @Test
    void registersLanguageSpecificMorphologyWithoutUniversalHeuristics() {
        SemanticMorphologyRegistry registry =
                SemanticTestMorphology.registry();

        assertThat(registry.supports("en")).isTrue();
        assertThat(registry.supports("ru")).isTrue();
        assertThat(registry.supports("kk")).isTrue();
        assertThat(registry.supports("zh")).isTrue();
        assertThat(registry.supports("de")).isFalse();
        assertThat(registry.require("en").language()).isEqualTo("en");
        assertThat(registry.require("ru").language()).isEqualTo("ru");
        assertThat(registry.require("kk").language()).isEqualTo("kk");
        assertThat(registry.require("zh").language()).isEqualTo("zh");

        assertThatThrownBy(() -> registry.require("de"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No semantic morphology normalizer");
    }
}
