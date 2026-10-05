package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;
import org.junit.jupiter.api.Test;

class IndustryProfileLanguageCoverageTest {

    @Test
    void incompleteYamlProfileFailsClosedAtConstruction() {
        IndustryDefinition definition = new IndustryDefinition(
                "incomplete",
                IndustryDomain.GENERAL,
                java.util.Map.of("en", "Incomplete"),
                java.util.Map.of(),
                java.util.Map.of()
        );

        assertThatThrownBy(() -> new GenericIndustryProfile(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all supported languages");
    }

    @Test
    void everyYamlIndustryProfileCoversEverySupportedLanguage() {
        Set<String> expected = java.util.Arrays.stream(
                        KnowledgeLanguage.values()
                )
                .filter(language -> language != KnowledgeLanguage.UNKNOWN)
                .map(KnowledgeLanguage::code)
                .collect(Collectors.toSet());

        var profiles = new YamlIndustryProfileLoader().loadAll();

        assertThat(profiles).isNotEmpty();
        profiles.forEach(profile -> {
            assertThat(profile.code().names().keySet())
                    .as(profile.code().id())
                    .containsExactlyInAnyOrderElementsOf(expected);
            profile.code().names().forEach((language, name) ->
                    assertThat(name)
                            .as(profile.code().id() + "/" + language)
                            .isNotBlank()
            );
        });
    }
}
