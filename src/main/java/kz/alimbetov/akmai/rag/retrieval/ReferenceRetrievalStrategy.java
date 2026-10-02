package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.stereotype.Component;

@Component
public class ReferenceRetrievalStrategy implements RetrievalStrategy {

    private final SearchProjectionRepository projectionRepository;
    private final ReferenceGraphRepository referenceGraphRepository;
    private final RetrievalProperties properties;

    public ReferenceRetrievalStrategy(
            SearchProjectionRepository projectionRepository,
            ReferenceGraphRepository referenceGraphRepository,
            RetrievalProperties properties
    ) {
        this.projectionRepository = projectionRepository;
        this.referenceGraphRepository = referenceGraphRepository;
        this.properties = properties;
    }

    @Override
    public RetrievalType type() {
        return RetrievalType.REFERENCE;
    }

    @Override
    public List<RetrievalHit> retrieve(
            QueryChunk queryChunk,
            RetrievalContext context
    ) {
        Map<String, List<String>> seedIdsByDocument = new LinkedHashMap<>();
        for (RetrievalHit hit : context.dependencyHits()) {
            if (hit.documentId() == null || hit.documentId().isBlank()
                    || hit.chunkId() == null || hit.chunkId().isBlank()) {
                continue;
            }
            seedIdsByDocument.computeIfAbsent(
                    hit.documentId(),
                    ignored -> new ArrayList<>()
            ).add(hit.chunkId());
        }

        List<RetrievalHit> result = new ArrayList<>();
        int remaining = properties.referenceLimit();

        for (Map.Entry<String, List<String>> entry : seedIdsByDocument.entrySet()) {
            if (remaining <= 0) {
                break;
            }
            String documentId = entry.getKey();
            List<String> seeds = entry.getValue().stream().distinct().toList();
            List<String> targetIds = referenceGraphRepository.resolveSameDocumentTargets(
                    documentId,
                    seeds,
                    context.accessLevels(),
                    remaining
            );
            List<SearchProjection> targets =
                    projectionRepository.findByDocumentAndChunkIds(
                            documentId,
                            targetIds,
                            context.accessLevels()
                    );
            for (SearchProjection target : targets) {
                if (remaining-- <= 0) {
                    break;
                }
                Map<String, Object> metadata =
                        new LinkedHashMap<>(target.metadata());
                metadata.put("language", target.language());
                metadata.put("sectionPath", target.sectionPath() == null
                        ? ""
                        : target.sectionPath());
                metadata.put("chunkIndex", target.chunkIndex());
                metadata.put("generation", target.generation());
                metadata.put("authorityTier", 0);
                metadata.put("authority", "EXACT_REFERENCE");
                metadata.put("expansion", "reference");
                result.add(new RetrievalHit(
                        RetrievalType.REFERENCE,
                        target.documentId(),
                        target.chunkId(),
                        target.text(),
                        metadata
                ));
            }
        }
        return List.copyOf(result);
    }
}
