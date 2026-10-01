package kz.alimbetov.akmai.knowledge.lifecycle;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class ChunkRetentionService {

    private final DocumentLifecycleRepository lifecycleRepository;
    private final SearchProjectionRepository projectionRepository;
    private final IdentifierSearchIndex identifierSearchIndex;
    private final VectorStore vectorStore;
    private final Clock clock;

    public ChunkRetentionService(
            DocumentLifecycleRepository lifecycleRepository,
            SearchProjectionRepository projectionRepository,
            IdentifierSearchIndex identifierSearchIndex,
            VectorStore vectorStore
    ) {
        this(
                lifecycleRepository,
                projectionRepository,
                identifierSearchIndex,
                vectorStore,
                Clock.systemUTC()
        );
    }

    ChunkRetentionService(
            DocumentLifecycleRepository lifecycleRepository,
            SearchProjectionRepository projectionRepository,
            IdentifierSearchIndex identifierSearchIndex,
            VectorStore vectorStore,
            Clock clock
    ) {
        this.lifecycleRepository = lifecycleRepository;
        this.projectionRepository = projectionRepository;
        this.identifierSearchIndex = identifierSearchIndex;
        this.vectorStore = vectorStore;
        this.clock = clock;
    }

    public RetentionCleanupResult cleanup(RetentionClaim claim) {
        Instant now = clock.instant();
        if (!lifecycleRepository.markDeleting(claim, now)) {
            return stale(claim);
        }

        try {
            List<String> chunkIds =
                    projectionRepository.findChunkIdsByDocumentId(claim.documentId());

            if (!lifecycleRepository.isCurrentClaim(claim)) {
                return stale(claim);
            }

            if (!chunkIds.isEmpty()) {
                vectorStore.delete(chunkIds);
            }

            /*
             * The fence is checked again after the non-transactional vector operation.
             * If reingestion won the race, this worker must not delete the newly
             * published PostgreSQL retrieval state.
             */
            if (!lifecycleRepository.isCurrentClaim(claim)) {
                return stale(claim);
            }

            identifierSearchIndex.deleteByDocumentId(claim.documentId());
            projectionRepository.deleteByDocumentId(claim.documentId());

            if (!lifecycleRepository.markDeleted(claim, clock.instant())) {
                return stale(claim);
            }

            return new RetentionCleanupResult(
                    claim.documentId(),
                    claim.generation(),
                    chunkIds.size(),
                    RetentionCleanupResult.Status.DELETED
            );
        } catch (RuntimeException exception) {
            lifecycleRepository.markFailed(
                    claim,
                    clock.instant(),
                    safeMessage(exception)
            );
            return new RetentionCleanupResult(
                    claim.documentId(),
                    claim.generation(),
                    0,
                    RetentionCleanupResult.Status.FAILED
            );
        }
    }

    private RetentionCleanupResult stale(RetentionClaim claim) {
        return new RetentionCleanupResult(
                claim.documentId(),
                claim.generation(),
                0,
                RetentionCleanupResult.Status.STALE_CLAIM
        );
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getClass().getSimpleName() + ": " + message;
    }
}
