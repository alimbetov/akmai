package kz.alimbetov.akmai.knowledge.ingestion;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentLifecycle;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentLifecycleRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentOperationLock;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionProperties;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository.VectorGenerationEntry;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionFactory;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class PersistenceCoordinator {

    private final VectorStore vectorStore;
    private final IdentifierSearchIndex identifierSearchIndex;
    private final SearchProjectionFactory projectionFactory;
    private final SearchProjectionRepository projectionRepository;
    private final DocumentLifecycleRepository lifecycleRepository;
    private final VectorGenerationRepository vectorGenerationRepository;
    private final DocumentOperationLock documentOperationLock;
    private final RetentionProperties retentionProperties;

    public PersistenceCoordinator(
            VectorStore vectorStore,
            IdentifierSearchIndex identifierSearchIndex,
            SearchProjectionFactory projectionFactory,
            SearchProjectionRepository projectionRepository,
            DocumentLifecycleRepository lifecycleRepository,
            VectorGenerationRepository vectorGenerationRepository,
            DocumentOperationLock documentOperationLock,
            RetentionProperties retentionProperties
    ) {
        this.vectorStore = vectorStore;
        this.identifierSearchIndex = identifierSearchIndex;
        this.projectionFactory = projectionFactory;
        this.projectionRepository = projectionRepository;
        this.lifecycleRepository = lifecycleRepository;
        this.vectorGenerationRepository = vectorGenerationRepository;
        this.documentOperationLock = documentOperationLock;
        this.retentionProperties = retentionProperties;
    }

    public void persist(List<EnrichedKnowledgeChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }

        List<SearchProjection> projections = chunks.stream()
                .map(projectionFactory::create)
                .toList();
        String documentId = singleDocumentId(projections);

        try (var ignored = documentOperationLock.acquire(documentId)) {
            DocumentLifecycle previous = lifecycleRepository
                    .findByDocumentId(documentId)
                    .orElse(null);
            long generation = lifecycleRepository.beginIngestion(
                    documentId,
                    retentionProperties.defaultPolicy(),
                    expiration()
            );

            replacePreviousGeneration(documentId, previous);
            projectionRepository.saveAll(projections);

            List<Document> vectors = projections.stream()
                    .map(projection -> new Document(
                            vectorId(documentId, generation, projection.chunkId()),
                            projection.embeddingText(),
                            vectorMetadata(projection, generation)
                    ))
                    .toList();
            vectorStore.add(vectors);

            vectorGenerationRepository.save(
                    documentId,
                    generation,
                    projections.stream()
                            .map(projection -> new VectorGenerationEntry(
                                    vectorId(documentId, generation, projection.chunkId()),
                                    projection.chunkId()
                            ))
                            .toList()
            );

            List<DocumentIdentifier> identifiers = identifiers(projections);
            if (!identifiers.isEmpty()) {
                identifierSearchIndex.index(identifiers);
            }

            if (!lifecycleRepository.publishIngestion(
                    documentId, generation, Instant.now()
            )) {
                throw new IllegalStateException(
                        "Lifecycle generation changed while document lock was held"
                );
            }
        }
    }

    private void replacePreviousGeneration(
            String documentId,
            DocumentLifecycle previous
    ) {
        List<String> vectorIds = previous == null
                ? List.of()
                : vectorGenerationRepository.findVectorIds(
                        documentId,
                        previous.generation()
                );

        if (vectorIds.isEmpty()) {
            // Compatibility cleanup for documents written before generation manifests existed.
            vectorIds = projectionRepository.findChunkIdsByDocumentId(documentId);
        }

        if (!vectorIds.isEmpty()) {
            vectorStore.delete(vectorIds);
        }
        identifierSearchIndex.deleteByDocumentId(documentId);
        projectionRepository.deleteByDocumentId(documentId);

        if (previous != null) {
            vectorGenerationRepository.deleteGeneration(
                    documentId,
                    previous.generation()
            );
        }
    }

    private String singleDocumentId(List<SearchProjection> projections) {
        List<String> documentIds = projections.stream()
                .map(SearchProjection::documentId)
                .distinct()
                .toList();
        if (documentIds.size() != 1) {
            throw new IllegalArgumentException(
                    "A persistence batch must contain exactly one document"
            );
        }
        return documentIds.getFirst();
    }

    private List<DocumentIdentifier> identifiers(List<SearchProjection> projections) {
        return projections.stream()
                .flatMap(projection -> projection.identifiers().stream()
                        .map(identifier -> new DocumentIdentifier(
                                projection.documentId(),
                                projection.chunkId(),
                                pageNumber(projection),
                                identifier.type(),
                                identifier.rawValue(),
                                identifier.normalizedValue(),
                                identifier.contextText(),
                                Instant.now()
                        )))
                .toList();
    }

    private String vectorId(String documentId, long generation, String chunkId) {
        String key = documentId + ":" + generation + ":" + chunkId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Instant expiration() {
        if (retentionProperties.defaultPolicy() == RetentionPolicy.PERMANENT) {
            return null;
        }
        return Instant.now().plus(retentionProperties.defaultTtl());
    }

    private int pageNumber(SearchProjection projection) {
        Object page = projection.metadata().get("pageFrom");
        return page instanceof Number number ? number.intValue() : 0;
    }

    private Map<String, Object> vectorMetadata(
            SearchProjection projection,
            long generation
    ) {
        Map<String, Object> metadata = new HashMap<>(projection.metadata());
        metadata.put("chunkId", projection.chunkId());
        metadata.put("documentId", projection.documentId());
        metadata.put("generation", generation);
        metadata.put("source", projection.metadata().getOrDefault("source", "unknown"));
        metadata.put("references", String.join(",", projection.references()));
        return metadata;
    }
}
