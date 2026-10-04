package kz.alimbetov.akmai.knowledge.semantic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SemanticMorphologyRegistry {

    private final Map<String, SemanticMorphologyNormalizer> byLanguage;

    public SemanticMorphologyRegistry(
            List<SemanticMorphologyNormalizer> normalizers
    ) {
        LinkedHashMap<String, SemanticMorphologyNormalizer> map =
                new LinkedHashMap<>();
        for (SemanticMorphologyNormalizer normalizer : normalizers) {
            if (map.putIfAbsent(
                    normalizer.language(),
                    normalizer
            ) != null) {
                throw new IllegalArgumentException(
                        "Duplicate morphology normalizer for "
                                + normalizer.language()
                );
            }
        }
        this.byLanguage = Map.copyOf(map);
    }

    public SemanticMorphologyNormalizer require(String language) {
        SemanticMorphologyNormalizer normalizer =
                byLanguage.get(language);
        if (normalizer == null) {
            throw new IllegalArgumentException(
                    "No semantic morphology normalizer for " + language
            );
        }
        return normalizer;
    }

    public boolean supports(String language) {
        return byLanguage.containsKey(language);
    }
}
