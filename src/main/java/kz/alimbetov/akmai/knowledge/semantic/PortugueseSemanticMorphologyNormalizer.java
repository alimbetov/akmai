package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PortugueseSemanticMorphologyNormalizer
        extends SuffixSemanticMorphologyNormalizer {

    public PortugueseSemanticMorphologyNormalizer() {
        super(
                "pt",
                List.of(
                        "ões", "ães", "ais", "éis", "es",
                        "os", "as", "s"
                ),
                List.of(
                        "izações", "ização", "ções", "ção",
                        "mentos", "mento", "idades", "idade",
                        "mente"
                )
        );
    }
}
