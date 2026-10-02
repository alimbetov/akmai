package kz.alimbetov.akmai.knowledge.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyContext;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.model.DocumentMetadata;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;
import kz.alimbetov.akmai.config.IdempotencyProperties;
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
    private final IngestionIdempotencyRepository idempotencyRepository;
    private final CanonicalRequestFingerprint requestFingerprint;
    private final IdempotencyProperties idempotencyProperties;

    public KnowledgeIngestionService(
            SemanticChunker semanticChunker,
            ParallelIngestionExecutor parallelIngestionExecutor,
            PersistenceCoordinator persistenceCoordinator,
            IngestionIdempotencyRepository idempotencyRepository,
            CanonicalRequestFingerprint requestFingerprint,
            IdempotencyProperties idempotencyProperties
    ) {
        this.semanticChunker = semanticChunker;
        this.parallelIngestionExecutor = parallelIngestionExecutor;
        this.persistenceCoordinator = persistenceCoordinator;
        this.idempotencyRepository = idempotencyRepository;
        this.requestFingerprint = requestFingerprint;
        this.idempotencyProperties = idempotencyProperties;
    }

    public KnowledgeIngestionResponse addText(AddKnowledgeRequest request) {
        return addText(request, null);
    }

    public KnowledgeIngestionResponse addText(
            AddKnowledgeRequest request,
            String idempotencyKey
    ) {
        IngestionIdempotencyContext idempotency = null;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var claim = idempotencyRepository.claim(
                    idempotencyKey,
                    request.documentId(),
                    requestFingerprint.fingerprint(request),
                    idempotencyProperties.leaseDuration()
            );
            if (claim.status()
                    == IngestionIdempotencyRepository.ClaimResult.Status.REPLAY) {
                return claim.response();
            }
            if (claim.status()
                    == IngestionIdempotencyRepository.ClaimResult.Status.IN_PROGRESS) {
                throw new IdempotencyConflictException(
                        "INGESTION_IN_PROGRESS",
                        "An ingestion with this Idempotency-Key is still in progress",
                        claim.retryAfterSeconds()
                );
            }
            idempotency = claim.context();
        }

        try {
            return ingest(request, idempotency);
        } catch (RuntimeException exception) {
            idempotencyRepository.fail(idempotency, exception.getMessage());
            throw exception;
        }
    }

    private KnowledgeIngestionResponse ingest(
            AddKnowledgeRequest request,
            IngestionIdempotencyContext idempotency
    ) {
        Map<String, Object> metadata = new HashMap<>(
                request.metadata() == null ? Map.of() : request.metadata()
        );
        metadata.put("source", request.source());
        metadata.put(DocumentMetadata.ACCESS_LEVEL, request.accessLevel());

        KnowledgeDocument document = new KnowledgeDocument(
                request.documentId(),
                request.title(),
                request.text(),
                KnowledgeLanguage.parse(request.language()).code(),
                request.domain(),
                metadata
        );

        List<KnowledgeChunk> chunks = semanticChunker.chunk(document);
        List<EnrichedKnowledgeChunk> enriched =
                parallelIngestionExecutor.execute(chunks);

        KnowledgeIngestionResponse response = new KnowledgeIngestionResponse(
                document.documentId(),
                chunks.size()
        );
        persistenceCoordinator.persist(
                enriched,
                idempotency,
                response,
                request.accessLevel()
        );
        return response;
    }
}
