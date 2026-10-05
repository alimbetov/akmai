package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;

final class SemanticTestMorphology {

    private SemanticTestMorphology() {
    }

    static SemanticMorphologyRegistry registry() {
        return new SemanticMorphologyRegistry(
                List.of(
                        new EnglishSemanticMorphologyNormalizer(),
                        new RussianSemanticMorphologyNormalizer(),
                        new KazakhSemanticMorphologyNormalizer(),
                        new ChineseSemanticMorphologyNormalizer(),
                        new GermanSemanticMorphologyNormalizer(),
                        new FrenchSemanticMorphologyNormalizer(),
                        new SpanishSemanticMorphologyNormalizer(),
                        new PortugueseSemanticMorphologyNormalizer(),
                        new ItalianSemanticMorphologyNormalizer(),
                        new TurkishSemanticMorphologyNormalizer(),
                        new GreekSemanticMorphologyNormalizer()
                )
        );
    }
}
