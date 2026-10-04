package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FrenchSemanticMorphologyNormalizer
        extends SuffixSemanticMorphologyNormalizer {

    public FrenchSemanticMorphologyNormalizer() {
        super(
                "fr",
                List.of(
                        "aux", "euses", "euse", "ées", "ée",
                        "es", "s"
                ),
                List.of(
                        "issements", "issement", "ations", "ation",
                        "ements", "ement", "iques", "ique", "ités",
                        "ité"
                )
        );
    }
}
