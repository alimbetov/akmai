package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;

public record MultilingualConceptSurfaceDefinition(
        String version,
        List<LanguageSurfaces> languages
) {
    public MultilingualConceptSurfaceDefinition {
        languages = List.copyOf(
                languages == null ? List.of() : languages
        );
    }

    public record LanguageSurfaces(
            String language,
            List<Surface> surfaces
    ) {
        public LanguageSurfaces {
            surfaces = List.copyOf(
                    surfaces == null ? List.of() : surfaces
            );
        }
    }

    public record Surface(
            String conceptId,
            String preferredPhrase,
            List<String> aliases
    ) {
        public Surface {
            aliases = List.copyOf(
                    aliases == null ? List.of() : aliases
            );
        }
    }
}
