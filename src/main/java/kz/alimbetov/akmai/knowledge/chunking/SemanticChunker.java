package kz.alimbetov.akmai.knowledge.chunking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.SemanticUnit;
import org.springframework.stereotype.Component;

@Component
public class SemanticChunker {

    private final TextNormalizer normalizer;
    private final StructuralUnitExtractor unitExtractor;
    private final DomainSemanticClassifier classifier;
    private final AtomicUnitProtector atomicUnitProtector;
    private final CrossReferenceExtractor referenceExtractor;
    private final EmbeddingTextBuilder embeddingTextBuilder;
    private final TokenEstimator tokenEstimator;
    private final ChunkingProperties properties;

    public SemanticChunker(
            TextNormalizer normalizer,
            StructuralUnitExtractor unitExtractor,
            DomainSemanticClassifier classifier,
            AtomicUnitProtector atomicUnitProtector,
            CrossReferenceExtractor referenceExtractor,
            EmbeddingTextBuilder embeddingTextBuilder,
            TokenEstimator tokenEstimator,
            ChunkingProperties properties
    ) {
        this.normalizer = normalizer;
        this.unitExtractor = unitExtractor;
        this.classifier = classifier;
        this.atomicUnitProtector = atomicUnitProtector;
        this.referenceExtractor = referenceExtractor;
        this.embeddingTextBuilder = embeddingTextBuilder;
        this.tokenEstimator = tokenEstimator;
        this.properties = properties;
    }

    public List<KnowledgeChunk> chunk(KnowledgeDocument document) {
        String normalized = normalizer.normalize(document.rawText());

        List<SemanticUnit> classified = unitExtractor.extract(document, normalized)
                .stream()
                .map(unit -> new SemanticUnit(
                        unit.text(),
                        unit.sectionPath(),
                        unit.type().name().equals("HEADING")
                                ? unit.type()
                                : classifier.classify(unit.text(), document.domain()),
                        unit.protectedAtom()
                ))
                .toList();

        List<SemanticUnit> protectedUnits =
                atomicUnitProtector.protect(classified, document.domain());

        List<List<SemanticUnit>> groups = group(protectedUnits);
        List<KnowledgeChunk> chunks = new ArrayList<>();

        for (int i = 0; i < groups.size(); i++) {
            List<SemanticUnit> group = groups.get(i);
            String rawText = group.stream()
                    .map(SemanticUnit::text)
                    .reduce((a, b) -> a + "\n\n" + b)
                    .orElse("");

            String sectionPath = group.stream()
                    .map(SemanticUnit::sectionPath)
                    .filter(value -> value != null && !value.isBlank())
                    .reduce((a, b) -> b)
                    .orElse(document.title());

            Map<String, Object> metadata = new LinkedHashMap<>(document.metadata());
            metadata.put("documentId", document.documentId());
            metadata.put("chunkIndex", i);
            metadata.put("language", document.language());
            metadata.put("domain", document.domain().name());
            metadata.put("sectionPath", sectionPath);

            chunks.add(new KnowledgeChunk(
                    UUID.randomUUID().toString(),
                    document.documentId(),
                    null,
                    i,
                    rawText,
                    normalizer.normalize(rawText),
                    embeddingTextBuilder.build(document, sectionPath, rawText),
                    document.title(),
                    sectionPath,
                    document.language(),
                    document.domain(),
                    referenceExtractor.extract(rawText),
                    metadata
            ));
        }

        return chunks;
    }

    private List<List<SemanticUnit>> group(List<SemanticUnit> units) {
        List<List<SemanticUnit>> groups = new ArrayList<>();
        List<SemanticUnit> current = new ArrayList<>();
        int currentTokens = 0;
        String currentSection = null;

        for (SemanticUnit unit : units) {
            int unitTokens = tokenEstimator.estimate(unit.text());
            boolean sectionChanged = currentSection != null
                    && unit.sectionPath() != null
                    && !currentSection.equals(unit.sectionPath());

            boolean wouldExceedSoftMax =
                    currentTokens + unitTokens > properties.softMaxTokens();

            boolean enoughContent =
                    currentTokens >= properties.minTokens();

            if (!current.isEmpty()
                    && ((sectionChanged && enoughContent)
                    || (wouldExceedSoftMax && enoughContent))) {
                groups.add(List.copyOf(current));
                current.clear();
                currentTokens = 0;
            }

            if (unitTokens > properties.hardMaxTokens()) {
                if (!current.isEmpty()) {
                    groups.add(List.copyOf(current));
                    current.clear();
                    currentTokens = 0;
                }
                groups.add(List.of(unit));
                currentSection = unit.sectionPath();
                continue;
            }

            current.add(unit);
            currentTokens += unitTokens;
            currentSection = unit.sectionPath();

            if (currentTokens >= properties.targetTokens()
                    && !unit.protectedAtom()) {
                groups.add(List.copyOf(current));
                current.clear();
                currentTokens = 0;
            }
        }

        if (!current.isEmpty()) {
            groups.add(List.copyOf(current));
        }

        return groups;
    }
}
