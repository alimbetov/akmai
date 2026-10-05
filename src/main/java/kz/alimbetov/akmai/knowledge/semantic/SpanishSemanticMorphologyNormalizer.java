package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SpanishSemanticMorphologyNormalizer
        extends SuffixSemanticMorphologyNormalizer {

    public SpanishSemanticMorphologyNormalizer() {
        super(
                "es",
                List.of(
                        "ces", "es", "os", "as", "s"
                ),
                List.of(
                        "amientos", "imientos", "aciones", "amiento",
                        "imiento", "ación", "idades", "idad", "mente"
                )
        );
    }
}
