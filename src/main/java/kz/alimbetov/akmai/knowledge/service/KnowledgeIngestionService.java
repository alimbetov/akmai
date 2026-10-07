package kz.alimbetov.akmai.knowledge.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.config.IdempotencyProperties;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.api.CanonicalDocument;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;
import kz.alimbetov.akmai.knowledge.chunking.HierarchicalChunker;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyContext;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.ingestion.EnrichedKnowledgeChunk;
import kz.alimbetov.akmai.knowledge.ingestion.ParallelIngestionExecutor;
import kz.alimbetov.akmai.knowledge.ingestion.PersistenceCoordinator;
import kz.alimbetov.akmai.knowledge.model.DocumentMetadata;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIngestionService implements KnowledgeIngestionPort {

    private final HierarchicalChunker hierarchicalChunker;
    private final ParallelIngestionExecutor parallelIngestionExecutor;
    private final PersistenceCoordinator persistenceCoordinator;
    private final IngestionIdempotencyRepository idempotencyRepository;
    private final CanonicalRequestFingerprint requestFingerprint;
    private final IdempotencyProperties idempotencyProperties;
    private final CanonicalDocumentMapper canonicalDocumentMapper;

    public KnowledgeIngestionService(
            HierarchicalChunker hierarchicalChunker,
            ParallelIngestionExecutor parallelIngestionExecutor,
            PersistenceCoordinator persistenceCoordinator,
            IngestionIdempotencyRepository idempotencyRepository,
            CanonicalRequestFingerprint requestFingerprint,
            IdempotencyProperties idempotencyProperties
    ) {
        this(
                hierarchicalChunker,
                parallelIngestionExecutor,
                persistenceCoordinator,
                idempotencyRepository,
                requestFingerprint,
                idempotencyProperties,
                new CanonicalDocumentMapper()
        );
    }

    @Autowired
    public KnowledgeIngestionService(
            HierarchicalChunker hierarchicalChunker,
            ParallelIngestionExecutor parallelIngestionExecutor,
            PersistenceCoordinator persistenceCoordinator,
            IngestionIdempotencyRepository idempotencyRepository,
            CanonicalRequestFingerprint requestFingerprint,
            IdempotencyProperties idempotencyProperties,
            CanonicalDocumentMapper canonicalDocumentMapper
    ) {
        this.hierarchicalChunker = hierarchicalChunker;
        this.parallelIngestionExecutor = parallelIngestionExecutor;
        this.persistenceCoordinator = persistenceCoordinator;
        this.idempotencyRepository = idempotencyRepository;
        this.requestFingerprint = requestFingerprint;
        this.idempotencyProperties = idempotencyProperties;
        this.canonicalDocumentMapper = canonicalDocumentMapper;
    }

    public KnowledgeIngestionResponse addText(AddKnowledgeRequest request) {
        return addText(request, null);
    }

    @Override
    public KnowledgeIngestionResponse addText(
            AddKnowledgeRequest request,
            String idempotencyKey
    ) {
        ClaimOutcome claim = claimOutcome(
                idempotencyKey,
                request.documentId(),
                requestFingerprint.fingerprint(request)
        );
        if (claim.response() != null) {
            return claim.response();
        }
        IngestionIdempotencyContext idempotency = claim.context();

        try {
            heartbeat(idempotency);
            return ingestText(request, idempotency);
        } catch (RuntimeException exception) {
            idempotencyRepository.fail(idempotency, exception.getMessage());
            throw exception;
        }
    }

    @Override
    public KnowledgeIngestionResponse addCanonical(
            CanonicalDocument document,
            String idempotencyKey
    ) {
        if (document == null) {
            throw new IllegalArgumentException("canonical document is required");
        }
        ClaimOutcome claim = claimOutcome(
                idempotencyKey,
                document.documentId(),
                requestFingerprint.fingerprint(document)
        );
        if (claim.response() != null) {
            return claim.response();
        }
        IngestionIdempotencyContext idempotency = claim.context();

        try {
            heartbeat(idempotency);
            CanonicalDocumentMapper.PreparedCanonicalDocument prepared =
                    canonicalDocumentMapper.prepare(document);
            List<KnowledgeChunk> chunks = hierarchicalChunker.chunk(
                    prepared.document(),
                    prepared.semanticUnits()
            );
            return persistChunks(
                    prepared.document(),
                    chunks,
                    document.accessLevel(),
                    idempotency
            );
        } catch (RuntimeException exception) {
            idempotencyRepository.fail(idempotency, exception.getMessage());
            throw exception;
        }
    }

    private KnowledgeIngestionResponse ingestText(
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

        List<KnowledgeChunk> chunks = hierarchicalChunker.chunk(document);
        return persistChunks(
                document,
                chunks,
                request.accessLevel(),
                idempotency
        );
    }

    private KnowledgeIngestionResponse persistChunks(
            KnowledgeDocument document,
            List<KnowledgeChunk> chunks,
            long accessLevel,
            IngestionIdempotencyContext idempotency
    ) {
        long searchableChunkCount = hierarchicalChunker.searchableChunkCount(chunks);
        if (searchableChunkCount == 0) {
            throw new IllegalArgumentException(
                    "Document produced no indexable chunks after normalization"
            );
        }
        heartbeat(idempotency);

        List<EnrichedKnowledgeChunk> enriched = parallelIngestionExecutor.execute(chunks);
        heartbeat(idempotency);

        KnowledgeIngestionResponse response = new KnowledgeIngestionResponse(
                document.documentId(),
                Math.toIntExact(searchableChunkCount)
        );
        persistenceCoordinator.persist(
                enriched,
                idempotency,
                response,
                accessLevel
        );
        return response;
    }

    private ClaimOutcome claimOutcome(
            String idempotencyKey,
            String documentId,
            String fingerprint
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new ClaimOutcome(null, null);
        }
        var claim = idempotencyRepository.claim(
                idempotencyKey,
                documentId,
                fingerprint,
                idempotencyProperties.leaseDuration()
        );
        if (claim.status()
                == IngestionIdempotencyRepository.ClaimResult.Status.REPLAY) {
            return new ClaimOutcome(null, claim.response());
        }
        if (claim.status()
                == IngestionIdempotencyRepository.ClaimResult.Status.IN_PROGRESS) {
            throw new IdempotencyConflictException(
                    "INGESTION_IN_PROGRESS",
                    "An ingestion with this Idempotency-Key is still in progress",
                    claim.retryAfterSeconds()
            );
        }
        return new ClaimOutcome(claim.context(), null);
    }

    private void heartbeat(IngestionIdempotencyContext idempotency) {
        idempotencyRepository.renew(
                idempotency,
                idempotencyProperties.leaseDuration()
        );
    }

    private record ClaimOutcome(
            IngestionIdempotencyContext context,
            KnowledgeIngestionResponse response
    ) {
    }
}
