package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(registry.supports("de")).isTrue();
        assertThat(registry.supports("fr")).isTrue();
        assertThat(registry.supports("es")).isTrue();
        assertThat(registry.supports("pt")).isTrue();
        assertThat(registry.supports("it")).isTrue();
        assertThat(registry.supports("tr")).isTrue();
        assertThat(registry.supports("el")).isTrue();
        assertThat(registry.require("en").language()).isEqualTo("en");
        assertThat(registry.require("ru").language()).isEqualTo("ru");
        assertThat(registry.require("kk").language()).isEqualTo("kk");
        assertThat(registry.require("zh").language()).isEqualTo("zh");
        assertThat(registry.require("de").language()).isEqualTo("de");
        assertThat(registry.require("fr").language()).isEqualTo("fr");
        assertThat(registry.require("es").language()).isEqualTo("es");
        assertThat(registry.require("pt").language()).isEqualTo("pt");
        assertThat(registry.require("it").language()).isEqualTo("it");
        assertThat(registry.require("tr").language()).isEqualTo("tr");
        assertThat(registry.require("el").language()).isEqualTo("el");
    }
}
