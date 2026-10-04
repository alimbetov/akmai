package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class EnglishSemanticConceptMatcher {

    private final SemanticConceptMatcher delegate;

    public EnglishSemanticConceptMatcher(
            EnglishSemanticConceptCatalog catalog
    ) {
        EnglishSemanticMorphologyNormalizer english =
                new EnglishSemanticMorphologyNormalizer();
        SemanticMorphologyRegistry morphologyRegistry =
                new SemanticMorphologyRegistry(List.of(english));
        this.delegate = new SemanticConceptMatcher(
                new SemanticConceptSurfaceRegistry(
                        catalog,
                        english
                ),
                morphologyRegistry
        );
    }

    @Autowired
    public EnglishSemanticConceptMatcher(
            SemanticConceptMatcher delegate
    ) {
        this.delegate = delegate;
    }

    public String version() {
        return delegate.version("en");
    }

    public List<SemanticConceptMatch> match(String text) {
        return delegate.match(text, "en");
    }
}
