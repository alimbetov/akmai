package kz.alimbetov.akmai.knowledge.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticSurfaceCollisionTest {

    private final EnglishSemanticConceptCatalog conceptCatalog =
            new EnglishSemanticConceptCatalog(new SemanticDomainCatalog());
    private final SemanticConceptSurfaceRegistry registry =
            new SemanticConceptSurfaceRegistry(
                    conceptCatalog,
                    SemanticTestMorphology.registry()
            );

    @Test
    void EnglishPreferredPhrasesAndAliasesHaveSingleConceptOwner() {
        Map<String, String> owners = new LinkedHashMap<>();

        for (SemanticConceptSurface surface : registry.surfaces("en")) {
            assertSingleOwner(
                    owners,
                    surface.preferredPhrase(),
                    surface.conceptId()
            );
            for (String alias : surface.aliases()) {
                assertSingleOwner(owners, alias, surface.conceptId());
            }
        }
    }

    private static void assertSingleOwner(
            Map<String, String> owners,
            String value,
            String conceptId
    ) {
        String normalized = EnglishSemanticConceptCatalog.normalizePhrase(value);
        String existing = owners.putIfAbsent(normalized, conceptId);
        assertThat(existing)
                .as("surface '%s' must have a single concept owner", normalized)
                .satisfiesAnyOf(
                        owner -> assertThat(owner).isNull(),
                        owner -> assertThat(owner).isEqualTo(conceptId)
                );
    }
}
