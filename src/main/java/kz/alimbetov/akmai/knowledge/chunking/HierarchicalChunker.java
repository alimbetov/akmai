package kz.alimbetov.akmai.knowledge.chunking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.ChunkRole;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.SemanticUnit;
import kz.alimbetov.akmai.knowledge.model.SemanticUnitType;
import org.springframework.stereotype.Component;

@Component
public class HierarchicalChunker {

    private static final long BOUNDARY_RANK_PENALTY = 30L;

    private final SemanticChunker semanticChunker;
    private final ParentChildProperties properties;
    private final TokenEstimator tokenEstimator;
    private final OversizedUnitSplitter oversizedUnitSplitter;
    private final EmbeddingTextBuilder embeddingTextBuilder;
    private final TextNormalizer normalizer;
    private final CrossReferenceExtractor referenceExtractor;
    private final ChunkIdentity chunkIdentity;

    public HierarchicalChunker(
            SemanticChunker semanticChunker,
            ParentChildProperties properties,
            TokenEstimator tokenEstimator,
            OversizedUnitSplitter oversizedUnitSplitter,
            EmbeddingTextBuilder embeddingTextBuilder,
            TextNormalizer normalizer,
            CrossReferenceExtractor referenceExtractor,
            ChunkIdentity chunkIdentity
    ) {
        this.semanticChunker = semanticChunker;
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
        this.oversizedUnitSplitter = oversizedUnitSplitter;
        this.embeddingTextBuilder = embeddingTextBuilder;
        this.normalizer = normalizer;
        this.referenceExtractor = referenceExtractor;
        this.chunkIdentity = chunkIdentity;
    }

    public List<KnowledgeChunk> chunk(KnowledgeDocument document) {
        return hierarchical(document, semanticChunker.chunk(document));
    }

    public List<KnowledgeChunk> chunk(
            KnowledgeDocument document,
            List<SemanticUnit> sourceUnits
    ) {
        return hierarchical(
                document,
                semanticChunker.chunk(document, sourceUnits)
        );
    }

    private List<KnowledgeChunk> hierarchical(
            KnowledgeDocument document,
            List<KnowledgeChunk> base
    ) {
        if (!properties.enabled() || base.isEmpty()) {
            return base;
        }

        List<KnowledgeChunk> result = new ArrayList<>();
        int childChunkIndex = 0;

        for (KnowledgeChunk parent : base) {
            List<String> childTexts = splitParent(document, parent);
            result.add(parent(parent, childTexts.size()));

            for (int childIndex = 0;
                    childIndex < childTexts.size();
                    childIndex++) {
                result.add(child(
                        document,
                        parent,
                        childTexts.get(childIndex),
                        childChunkIndex++,
                        childIndex,
                        childTexts.size()
                ));
            }
        }

        return List.copyOf(result);
    }

    public long searchableChunkCount(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return 0L;
        }
        return chunks.stream()
                .filter(chunk -> ChunkRole.isSearchable(chunk.metadata()))
                .count();
    }

    private KnowledgeChunk parent(
            KnowledgeChunk source,
            int childCount
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>(source.metadata());
        metadata.put(ChunkRole.METADATA_KEY, ChunkRole.PARENT.name());
        metadata.put(ChunkRole.CHILD_COUNT_KEY, childCount);
        metadata.put(
                ChunkRole.ESTIMATED_TOKENS_KEY,
                tokenEstimator.estimate(source.embeddingText())
        );

        return new KnowledgeChunk(
                source.chunkId(),
                source.documentId(),
                null,
                source.chunkIndex(),
                source.rawText(),
                source.normalizedText(),
                source.embeddingText(),
                source.title(),
                source.sectionPath(),
                source.language(),
                source.domain(),
                source.references(),
                Map.copyOf(metadata)
        );
    }

    private KnowledgeChunk child(
            KnowledgeDocument document,
            KnowledgeChunk parent,
            String rawText,
            int chunkIndex,
            int childIndex,
            int childCount
    ) {
        String normalizedText = normalizer.normalize(rawText);
        String embeddingText = embeddingTextBuilder.build(
                document,
                parent.sectionPath(),
                rawText
        );
        int estimatedTokens = tokenEstimator.estimate(embeddingText);
        if (estimatedTokens > properties.childMaxTokens()) {
            throw new IllegalStateException(
                    "Child embedding payload exceeds configured child maximum"
            );
        }

        Map<String, Object> metadata = new LinkedHashMap<>(parent.metadata());
        metadata.put("chunkIndex", chunkIndex);
        metadata.put(ChunkRole.METADATA_KEY, ChunkRole.CHILD.name());
        metadata.put(ChunkRole.PARENT_CHUNK_ID_KEY, parent.chunkId());
        metadata.put(ChunkRole.PARENT_CHUNK_INDEX_KEY, parent.chunkIndex());
        metadata.put(ChunkRole.CHILD_INDEX_KEY, childIndex);
        metadata.put(ChunkRole.CHILD_COUNT_KEY, childCount);
        metadata.put(ChunkRole.ESTIMATED_TOKENS_KEY, estimatedTokens);

        return new KnowledgeChunk(
                chunkIdentity.createChild(
                        document.documentId(),
                        parent.chunkId(),
                        childIndex,
                        parent.sectionPath(),
                        normalizedText
                ),
                document.documentId(),
                parent.chunkId(),
                chunkIndex,
                rawText,
                normalizedText,
                embeddingText,
                parent.title(),
                parent.sectionPath(),
                parent.language(),
                parent.domain(),
                referenceExtractor.extract(rawText, parent.language()),
                Map.copyOf(metadata)
        );
    }

    private List<String> splitParent(
            KnowledgeDocument document,
            KnowledgeChunk parent
    ) {
        String text = parent.rawText() == null ? "" : parent.rawText().trim();
        if (text.isEmpty()) {
            return List.of();
        }

        int envelopeTokens = childTokens(document, parent, "");
        if (envelopeTokens >= properties.childMaxTokens()) {
            throw new IllegalStateException(
                    "Child embedding envelope leaves no payload token budget"
            );
        }

        int totalTokens = childTokens(document, parent, text);
        if (totalTokens <= properties.childMaxTokens()) {
            return List.of(text);
        }

        if (totalTokens <= properties.childMaxTokens() * 2) {
            List<String> balanced = balancedTwo(document, parent, text);
            if (!balanced.isEmpty()) {
                return balanced;
            }
        }

        int payloadBudget = Math.max(
                1,
                properties.childMaxTokens() - envelopeTokens
        );
        int payloadMinimum = Math.max(
                1,
                properties.childMinTokens() - envelopeTokens
        );
        SemanticUnit unit = new SemanticUnit(
                text,
                parent.sectionPath(),
                SemanticUnitType.PARAGRAPH,
                false
        );
        List<String> initial = oversizedUnitSplitter
                .split(
                        unit,
                        payloadMinimum,
                        payloadBudget,
                        parent.language()
                )
                .stream()
                .map(SemanticUnit::text)
                .toList();

        List<String> bounded = new ArrayList<>();
        for (String part : initial) {
            bounded.addAll(enforceChildMaximum(
                    document,
                    parent,
                    part,
                    payloadBudget
            ));
        }
        return List.copyOf(bounded);
    }

    private List<String> balancedTwo(
            KnowledgeDocument document,
            KnowledgeChunk parent,
            String text
    ) {
        int bestBoundary = -1;
        long bestScore = Long.MAX_VALUE;

        for (int index = 1; index < text.length(); index++) {
            if (index < text.length()
                    && Character.isLowSurrogate(text.charAt(index))
                    && Character.isHighSurrogate(text.charAt(index - 1))) {
                continue;
            }
            int boundaryRank = ChunkBoundarySelector.rank(
                    text,
                    index,
                    parent.language()
            );
            if (boundaryRank == ChunkBoundarySelector.RANK_NONE) {
                continue;
            }

            String left = text.substring(0, index).trim();
            String right = text.substring(index).trim();
            if (left.isEmpty() || right.isEmpty()) {
                continue;
            }

            int leftTokens = childTokens(document, parent, left);
            int rightTokens = childTokens(document, parent, right);
            if (leftTokens > properties.childMaxTokens()
                    || rightTokens > properties.childMaxTokens()) {
                continue;
            }

            long score = Math.abs(
                    leftTokens - properties.childTargetTokens()
            ) + Math.abs(
                    rightTokens - properties.childTargetTokens()
            );
            if (leftTokens < properties.childMinTokens()) {
                score += 20L * (
                        properties.childMinTokens() - leftTokens
                );
            }
            if (rightTokens < properties.childMinTokens()) {
                score += 20L * (
                        properties.childMinTokens() - rightTokens
                );
            }
            score += BOUNDARY_RANK_PENALTY * (
                    ChunkBoundarySelector.RANK_STRUCTURAL - boundaryRank
            );

            if (score < bestScore) {
                bestScore = score;
                bestBoundary = index;
            }
        }

        if (bestBoundary < 0) {
            return List.of();
        }
        return List.of(
                text.substring(0, bestBoundary).trim(),
                text.substring(bestBoundary).trim()
        );
    }

    private List<String> enforceChildMaximum(
            KnowledgeDocument document,
            KnowledgeChunk parent,
            String text,
            int initialPayloadBudget
    ) {
        if (childTokens(document, parent, text)
                <= properties.childMaxTokens()) {
            return List.of(text);
        }

        int envelopeTokens = childTokens(document, parent, "");
        int preferredMinimum = Math.max(
                1,
                properties.childMinTokens() - envelopeTokens
        );
        int budget = Math.max(1, initialPayloadBudget - 1);
        while (budget >= 1) {
            SemanticUnit unit = new SemanticUnit(
                    text,
                    parent.sectionPath(),
                    SemanticUnitType.PARAGRAPH,
                    false
            );
            List<SemanticUnit> parts = oversizedUnitSplitter.split(
                    unit,
                    Math.min(preferredMinimum, budget),
                    budget,
                    parent.language()
            );
            boolean allFit = parts.stream().allMatch(part ->
                    childTokens(document, parent, part.text())
                            <= properties.childMaxTokens()
            );
            if (allFit && !(parts.size() == 1
                    && parts.getFirst().text().equals(text))) {
                return parts.stream().map(SemanticUnit::text).toList();
            }
            if (budget == 1) {
                break;
            }
            budget = Math.max(1, budget / 2);
        }

        throw new IllegalStateException(
                "Unable to split child within configured token maximum"
        );
    }

    private int childTokens(
            KnowledgeDocument document,
            KnowledgeChunk parent,
            String text
    ) {
        return tokenEstimator.estimate(
                embeddingTextBuilder.build(
                        document,
                        parent.sectionPath(),
                        text
                )
        );
    }
}
