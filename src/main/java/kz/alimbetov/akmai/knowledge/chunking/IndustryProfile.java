package kz.alimbetov.akmai.knowledge.chunking;

import java.util.Optional;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;

public interface IndustryProfile {

    IndustryCode code();

    Optional<HeadingMatch> matchHeading(
            String line,
            LanguageProfile language
    );

    default Optional<SemanticUnitType> classifyType(
            String text,
            LanguageProfile language
    ) {
        return Optional.empty();
    }

    record HeadingMatch(
            int level,
            String text
    ) {
        public HeadingMatch {
            if (level < 1 || level > 7) {
                throw new IllegalArgumentException(
                        "heading level must be between 1 and 7"
                );
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException(
                        "heading text must not be blank"
                );
            }
        }
    }
}
