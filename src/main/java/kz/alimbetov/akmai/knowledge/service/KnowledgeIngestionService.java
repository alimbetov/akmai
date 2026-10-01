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
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIngestionService {

    private final SemanticChunker semanticChunker;
    private final ParallelIngestionExecutor parallelIngestionExecutor;
    private final PersistenceCoordinator persistenceCoordinator;

    public KnowledgeIngestionService(
            SemanticChunker semanticChunker,
            ParallelIngestionExecutor parallelIngestionExecutor,
            PersistenceCoordinator persistenceCoordinator
    ) {
        this.semanticChunker = semanticChunker;
        this.parallelIngestionExecutor = parallelIngestionExecutor;
        this.persistenceCoordinator = persistenceCoordinator;
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

        persistenceCoordinator.persist(enriched);

        return new KnowledgeIngestionResponse(
                document.documentId(),
                chunks.size()
        );
    }
}
