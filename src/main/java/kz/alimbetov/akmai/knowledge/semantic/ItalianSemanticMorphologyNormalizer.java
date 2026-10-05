package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ItalianSemanticMorphologyNormalizer
        extends SuffixSemanticMorphologyNormalizer {

    public ItalianSemanticMorphologyNormalizer() {
        super(
                "it",
                List.of(
                        "ghi", "che", "i", "e"
                ),
                List.of(
                        "izzazioni", "izzazione", "zioni", "zione",
                        "menti", "mento", "ità", "iche", "ici",
                        "ica", "ico"
                )
        );
    }
}
