package kz.alimbetov.akmai.knowledge.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;
import kz.alimbetov.akmai.knowledge.chunking.SemanticChunker;
import kz.alimbetov.akmai.knowledge.ingestion.EnrichedKnowledgeChunk;
import kz.alimbetov.akmai.knowledge.ingestion.ParallelIngestionExecutor;
import kz.alimbetov.akmai.knowledge.ingestion.PersistenceCoordinator;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentLifecycleRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIngestionService {

    private final SemanticChunker semanticChunker;
    private final ParallelIngestionExecutor parallelIngestionExecutor;
    private final PersistenceCoordinator persistenceCoordinator;
    private final DocumentLifecycleRepository lifecycleRepository;

    public KnowledgeIngestionService(
            SemanticChunker semanticChunker,
            ParallelIngestionExecutor parallelIngestionExecutor,
            PersistenceCoordinator persistenceCoordinator,
            DocumentLifecycleRepository lifecycleRepository
    ) {
        this.semanticChunker = semanticChunker;
        this.parallelIngestionExecutor = parallelIngestionExecutor;
        this.persistenceCoordinator = persistenceCoordinator;
        this.lifecycleRepository = lifecycleRepository;
    }

    public KnowledgeIngestionResponse addText(AddKnowledgeRequest request) {
        Map<String, Object> metadata = new HashMap<>(
                request.metadata() == null ? Map.of() : request.metadata()
        );
        metadata.put("source", request.source());

        KnowledgeDocument document = new KnowledgeDocument(
                request.documentId(),
                request.title(),
                request.text(),
                request.language(),
                request.domain(),
                metadata
        );

        List<KnowledgeChunk> chunks = semanticChunker.chunk(document);
        List<EnrichedKnowledgeChunk> enriched =
                parallelIngestionExecutor.execute(chunks);

        long generation = lifecycleRepository.reserveGeneration(document.documentId());
        persistenceCoordinator.persist(enriched, generation);
        if (!lifecycleRepository.activate(
                document.documentId(),
                generation,
                RetentionPolicy.PERMANENT,
                null
        )) {
            throw new IllegalStateException(
                    "Lifecycle generation changed before ingestion publication"
            );
        }

        return new KnowledgeIngestionResponse(
                document.documentId(),
                chunks.size()
        );
    }
}
