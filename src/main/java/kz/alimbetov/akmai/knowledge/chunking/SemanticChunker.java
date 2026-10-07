package kz.alimbetov.akmai.knowledge.chunking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingTokenBudgetService;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.model.SemanticUnit;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import kz.alimbetov.akmai.knowledge.model.UnitProvenance;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final OversizedUnitSplitter oversizedUnitSplitter;
    private final ChunkIdentity chunkIdentity;
    private EmbeddingTokenBudgetService embeddingTokenBudgetService;
    private IndustryProfileRegistry industryProfileRegistry;

    public SemanticChunker(
            TextNormalizer normalizer,
            StructuralUnitExtractor unitExtractor,
            DomainSemanticClassifier classifier,
            AtomicUnitProtector atomicUnitProtector,
            CrossReferenceExtractor referenceExtractor,
            EmbeddingTextBuilder embeddingTextBuilder,
            TokenEstimator tokenEstimator,
            ChunkingProperties properties,
            OversizedUnitSplitter oversizedUnitSplitter,
            ChunkIdentity chunkIdentity
    ) {
        this.normalizer = normalizer;
        this.unitExtractor = unitExtractor;
        this.classifier = classifier;
        this.atomicUnitProtector = atomicUnitProtector;
        this.referenceExtractor = referenceExtractor;
        this.embeddingTextBuilder = embeddingTextBuilder;
        this.tokenEstimator = tokenEstimator;
        this.properties = properties;
        this.oversizedUnitSplitter = oversizedUnitSplitter;
        this.chunkIdentity = chunkIdentity;
    }

    @Autowired(required = false)
    void setEmbeddingTokenBudgetService(
            EmbeddingTokenBudgetService embeddingTokenBudgetService
    ) {
        this.embeddingTokenBudgetService = embeddingTokenBudgetService;
    }

    @Autowired(required = false)
    void setIndustryProfileRegistry(
            IndustryProfileRegistry industryProfileRegistry
    ) {
        this.industryProfileRegistry = industryProfileRegistry;
    }

    public List<KnowledgeChunk> chunk(KnowledgeDocument document) {
        String normalized = normalizer.normalize(document.rawText());
        return chunk(
                document,
                unitExtractor.extract(document, normalized)
        );
    }

    public List<KnowledgeChunk> chunk(
            KnowledgeDocument document,
            List<SemanticUnit> sourceUnits
    ) {
        LanguageProfile languageProfile =
                LanguageProfiles.forCode(document.language());
        IndustryProfile industryProfile = industryProfileRegistry == null
                ? IndustryProfiles.defaultFor(document.domain())
                : industryProfileRegistry.forDocument(document);

        List<SemanticUnit> classified = (sourceUnits == null ? List.<SemanticUnit>of() : sourceUnits)
                .stream()
                .filter(java.util.Objects::nonNull)
                .map(unit -> classifyUnit(
                        unit,
                        document,
                        languageProfile,
                        industryProfile
                ))
                .filter(java.util.Objects::nonNull)
                .toList();

        List<SemanticUnit> protectedUnits =
                atomicUnitProtector.protect(classified, document.domain());

        List<SemanticUnit> boundedUnits = protectedUnits.stream()
                .flatMap(unit -> splitToEmbeddingBudget(unit, document).stream())
                .toList();

        List<List<SemanticUnit>> groups = group(
                boundedUnits,
                document
        );
        List<KnowledgeChunk> chunks = new ArrayList<>();

        for (int i = 0; i < groups.size(); i++) {
            List<SemanticUnit> group = groups.get(i);
            String rawText = rawText(group);
            String sectionPath = sectionPath(group, document);
            String normalizedChunk = normalizer.normalize(rawText);
            String embeddingText = embeddingTextBuilder.build(
                    document,
                    sectionPath,
                    rawText
            );
            if (tokenEstimator.estimate(embeddingText)
                    > properties.hardMaxTokens()) {
                throw new IllegalStateException(
                        "Final embedding payload exceeds hard token limit"
                );
            }
            if (embeddingTokenBudgetService != null) {
                embeddingTokenBudgetService.assertFits(embeddingText);
            }

            Map<String, Object> metadata = new LinkedHashMap<>(
                    document.metadata() == null ? Map.of() : document.metadata()
            );
            metadata.values().removeIf(java.util.Objects::isNull);
            metadata.put("documentId", document.documentId());
            metadata.put("chunkIndex", i);
            metadata.put("language", document.language());
            metadata.put("domain", document.domain().name());
            metadata.put("industryProfile", industryProfile.code().id());
            metadata.put("sectionPath", sectionPath);
            applyProvenance(metadata, provenance(group));

            chunks.add(new KnowledgeChunk(
                    chunkIdentity.create(
                            document.documentId(),
                            i,
                            sectionPath,
                            normalizedChunk
                    ),
                    document.documentId(),
                    null,
                    i,
                    rawText,
                    normalizedChunk,
                    embeddingText,
                    document.title(),
                    sectionPath,
                    document.language(),
                    document.domain(),
                    referenceExtractor.extract(rawText, document.language()),
                    Map.copyOf(metadata)
            ));
        }

        return List.copyOf(chunks);
    }

    private SemanticUnit classifyUnit(
            SemanticUnit unit,
            KnowledgeDocument document,
            LanguageProfile languageProfile,
            IndustryProfile industryProfile
    ) {
        String text = normalizer.normalize(unit.text());
        if (text == null || text.isBlank()) {
            return null;
        }
        SemanticUnitType type = unit.type() == SemanticUnitType.HEADING
                ? SemanticUnitType.HEADING
                : industryProfile.classifyType(
                        text,
                        languageProfile
                ).orElseGet(() -> classifier.classify(
                        text,
                        document.domain()
                ));
        String sectionPath = unit.sectionPath() == null
                || unit.sectionPath().isBlank()
                ? document.title()
                : unit.sectionPath();
        return new SemanticUnit(
                text,
                sectionPath,
                type,
                unit.protectedAtom(),
                unit.structuralRole(),
                unit.provenance()
        );
    }

    private void applyProvenance(
            Map<String, Object> metadata,
            UnitProvenance provenance
    ) {
        if (provenance == null) {
            return;
        }
        if (!provenance.blockIds().isEmpty()) {
            metadata.put("blockIds", provenance.blockIds());
        }
        if (provenance.pageFrom() != null) {
            metadata.put("pageFrom", provenance.pageFrom());
        }
        if (provenance.pageTo() != null) {
            metadata.put("pageTo", provenance.pageTo());
        }
    }

    private UnitProvenance provenance(List<SemanticUnit> group) {
        UnitProvenance result = null;
        for (SemanticUnit unit : group) {
            result = UnitProvenance.merge(result, unit.provenance());
        }
        return result;
    }

    private List<SemanticUnit> splitToEmbeddingBudget(
            SemanticUnit unit,
            KnowledgeDocument document
    ) {
        int overhead = tokenEstimator.estimate(
                embeddingTextBuilder.build(
                        document,
                        unit.sectionPath(),
                        ""
                )
        );
        int payloadBudget = Math.max(
                1,
                properties.hardMaxTokens() - overhead
        );

        while (true) {
            List<SemanticUnit> parts =
                    oversizedUnitSplitter.split(unit, payloadBudget);
            boolean allFit = parts.stream().allMatch(part -> {
                String embeddingText = embeddingTextBuilder.build(
                        document,
                        part.sectionPath(),
                        part.text()
                );
                return tokenEstimator.estimate(embeddingText)
                                <= properties.hardMaxTokens()
                        && (embeddingTokenBudgetService == null
                        || embeddingTokenBudgetService.fits(embeddingText));
            });
            if (allFit) {
                return parts;
            }
            if (payloadBudget == 1) {
                throw new IllegalArgumentException(
                        "Embedding envelope exceeds configured model context window"
                );
            }
            payloadBudget = Math.max(1, payloadBudget / 2);
        }
    }

    private List<List<SemanticUnit>> group(
            List<SemanticUnit> units,
            KnowledgeDocument document
    ) {
        List<List<SemanticUnit>> groups = new ArrayList<>();
        List<SemanticUnit> current = new ArrayList<>();
        int currentTokens = 0;
        String currentSection = null;

        for (SemanticUnit unit : units) {
            int unitTokens = tokenEstimator.estimate(unit.text());
            boolean sectionChanged = currentSection != null
                    && unit.sectionPath() != null
                    && !currentSection.equals(unit.sectionPath());
            boolean enoughContent = currentTokens >= properties.minTokens();
            boolean medicalAtomicBoundary = document.domain() == KnowledgeDomain.MEDICAL
                    && unit.protectedAtom()
                    && unit.type() != SemanticUnitType.HEADING
                    && !current.isEmpty();

            List<SemanticUnit> candidate = new ArrayList<>(current);
            candidate.add(unit);
            String candidateEmbedding = embeddingTextBuilder.build(
                    document,
                    sectionPath(candidate, document),
                    rawText(candidate)
            );
            boolean exceedsHard = tokenEstimator.estimate(candidateEmbedding)
                    > properties.hardMaxTokens();
            boolean exceedsModelBudget = embeddingTokenBudgetService != null
                    && !embeddingTokenBudgetService.fits(candidateEmbedding);
            boolean exceedsSoft = currentTokens + unitTokens
                    > properties.softMaxTokens();

            if (!current.isEmpty()
                    && (exceedsHard
                    || exceedsModelBudget
                    || medicalAtomicBoundary
                    || (sectionChanged && enoughContent)
                    || (exceedsSoft && enoughContent))) {
                groups.add(List.copyOf(current));
                current.clear();
                currentTokens = 0;
            }

            current.add(unit);
            currentTokens += unitTokens;
            currentSection = unit.sectionPath();

            if (embeddingTokens(current, document) > properties.hardMaxTokens()) {
                throw new IllegalStateException(
                        "A single bounded semantic unit exceeds final hard limit"
                );
            }

            if ((document.domain() == KnowledgeDomain.MEDICAL
                    && unit.protectedAtom()
                    && unit.type() != SemanticUnitType.HEADING)
                    || (currentTokens >= properties.targetTokens()
                    && !unit.protectedAtom())) {
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

    private int embeddingTokens(
            List<SemanticUnit> units,
            KnowledgeDocument document
    ) {
        return tokenEstimator.estimate(
                embeddingTextBuilder.build(
                        document,
                        sectionPath(units, document),
                        rawText(units)
                )
        );
    }

    private String rawText(List<SemanticUnit> group) {
        return group.stream()
                .map(SemanticUnit::text)
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
    }

    private String sectionPath(
            List<SemanticUnit> group,
            KnowledgeDocument document
    ) {
        return group.stream()
                .map(SemanticUnit::sectionPath)
                .filter(value -> value != null && !value.isBlank())
                .reduce((a, b) -> b)
                .orElse(document.title());
    }
}
