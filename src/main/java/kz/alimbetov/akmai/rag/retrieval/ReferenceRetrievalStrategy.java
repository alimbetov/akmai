package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.stereotype.Component;

@Component
public class ReferenceRetrievalStrategy implements RetrievalStrategy {

    private final PublishedSearchProjectionReader projectionRepository;
    private final ReferenceGraphRepository referenceGraphRepository;
    private final RetrievalProperties properties;

    public ReferenceRetrievalStrategy(
            PublishedSearchProjectionReader projectionRepository,
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
        Map<DocumentGeneration, List<String>> seedIds = new LinkedHashMap<>();
        for (RetrievalHit hit : context.dependencyHits()) {
            long generation = hit.generation();
            long accessLevel = effectiveAccessLevel(
                    hit,
                    context.accessLevels()
            );
            if (accessLevel <= 0
                    || generation <= 0
                    || hit.documentId() == null
                    || hit.documentId().isBlank()
                    || hit.chunkId() == null
                    || hit.chunkId().isBlank()) {
                continue;
            }
            seedIds.computeIfAbsent(
                    new DocumentGeneration(
                            accessLevel,
                            hit.documentId(),
                            generation
                    ),
                    ignored -> new ArrayList<>()
            ).add(hit.chunkId());
        }

        List<RetrievalHit> result = new ArrayList<>();
        int remaining = properties.referenceLimit();

        for (Map.Entry<DocumentGeneration, List<String>> entry : seedIds.entrySet()) {
            if (remaining <= 0) {
                break;
            }
            DocumentGeneration scope = entry.getKey();
            List<String> seeds = entry.getValue().stream().distinct().toList();
            List<String> targetIds = referenceGraphRepository.resolveSameDocumentTargets(
                    scope.documentId(),
                    scope.generation(),
                    seeds,
                    java.util.Set.of(scope.accessLevel()),
                    remaining
            );
            List<SearchProjection> targets =
                    projectionRepository.findByDocumentGenerationAndChunkIds(
                            scope.documentId(),
                            scope.generation(),
                            targetIds,
                            java.util.Set.of(scope.accessLevel())
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
                        target.accessLevel(),
                        target.documentId(),
                        target.generation(),
                        target.chunkId(),
                        target.text(),
                        metadata
                ));
            }
        }
        return List.copyOf(result);
    }

    private long effectiveAccessLevel(
            RetrievalHit hit,
            java.util.Set<Long> allowed
    ) {
        if (hit.accessLevel() <= 0) {
            return 0L;
        }
        return allowed.contains(hit.accessLevel())
                ? hit.accessLevel()
                : 0L;
    }

    private record DocumentGeneration(
            long accessLevel,
            String documentId,
            long generation
    ) {
    }
}
