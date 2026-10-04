package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GermanSemanticMorphologyNormalizer
        extends SuffixSemanticMorphologyNormalizer {

    public GermanSemanticMorphologyNormalizer() {
        super(
                "de",
                List.of(
                        "ern", "em", "en", "er", "es", "e", "n", "s"
                ),
                List.of(
                        "ungen", "heiten", "keiten", "ischen",
                        "liche", "lichen", "ung", "heit", "keit",
                        "isch", "lich"
                )
        );
    }
}
