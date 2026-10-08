package kz.alimbetov.akmai.knowledge.projection;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import kz.alimbetov.akmai.knowledge.model.ChunkRole;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies the configured retrieval transaction timeout and synchronous
 * lifecycle/TTL eligibility to published read paths without changing
 * ingestion/write transaction semantics.
 *
 * <p>Parent projections are addressable context records, not primary retrieval
 * candidates. Search-oriented methods therefore expose only searchable
 * projections while direct keyed reads remain hierarchy-aware.</p>
 */
@Component
@Primary
public class TimedPublishedSearchProjectionReader
        implements PublishedSearchProjectionReader {

    private static final int SEARCH_OVERSAMPLE_FACTOR = 3;
    private static final int SEARCH_OVERSAMPLE_MAX = 1000;

    private final PostgresSearchProjectionRepository delegate;
    private final TransactionTemplate transactionTemplate;
    private final PublishedProjectionLifecycleEligibility lifecycleEligibility;

    public TimedPublishedSearchProjectionReader(
            PostgresSearchProjectionRepository delegate,
            @Qualifier("retrievalTransactionTemplate")
            TransactionTemplate transactionTemplate,
            PublishedProjectionLifecycleEligibility lifecycleEligibility
    ) {
        this.delegate = delegate;
        this.transactionTemplate = transactionTemplate;
        this.lifecycleEligibility = lifecycleEligibility;
    }

    @Override
    public List<SearchProjection> findByDocumentAndChunkIds(
            String documentId,
            List<String> chunkIds,
            Set<Long> accessLevels
    ) {
        return eligible(read(() -> delegate.findByDocumentAndChunkIds(
                documentId,
                chunkIds,
                accessLevels
        )));
    }

    @Override
    public List<SearchProjection> findByDocumentGenerationAndChunkIds(
            String documentId,
            long generation,
            List<String> chunkIds,
            Set<Long> accessLevels
    ) {
        return eligible(read(() -> delegate.findByDocumentGenerationAndChunkIds(
                documentId,
                generation,
                chunkIds,
                accessLevels
        )));
    }

    @Override
    public List<SearchProjection> findPublishedByKeys(
            List<ProjectionKey> keys,
            Set<Long> accessLevels
    ) {
        return eligible(read(() -> delegate.findPublishedByKeys(
                keys,
                accessLevels
        )));
    }

    @Override
    public List<SearchProjection> findAdjacent(
            String documentId,
            long generation,
            int chunkIndex,
            int radius,
            Set<Long> accessLevels
    ) {
        return searchable(eligible(read(() -> delegate.findAdjacent(
                documentId,
                generation,
                chunkIndex,
                radius,
                accessLevels
        ))), Integer.MAX_VALUE);
    }

    @Override
    public List<SearchProjection> searchLexical(
            String query,
            String language,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }
        int oversampled = oversampledLimit(limit);
        return searchable(eligible(read(() -> delegate.searchLexical(
                query,
                language,
                documentIds,
                accessLevels,
                oversampled
        ))), limit);
    }

    @Override
    public List<SearchProjection> searchSemanticConcepts(
            List<String> conceptIds,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }
        int oversampled = oversampledLimit(limit);
        return searchable(eligible(read(() -> delegate.searchSemanticConcepts(
                conceptIds,
                documentIds,
                accessLevels,
                oversampled
        ))), limit);
    }

    private List<SearchProjection> eligible(List<SearchProjection> values) {
        return lifecycleEligibility.filter(values);
    }

    private List<SearchProjection> searchable(
            List<SearchProjection> values,
            int limit
    ) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null
                        && ChunkRole.isSearchable(value.metadata()))
                .limit(limit)
                .toList();
    }

    private int oversampledLimit(int limit) {
        long candidate = (long) limit * SEARCH_OVERSAMPLE_FACTOR;
        return (int) Math.min(
                SEARCH_OVERSAMPLE_MAX,
                Math.max(limit, candidate)
        );
    }

    private <T> T read(Supplier<T> action) {
        T value = transactionTemplate.execute(status -> action.get());
        if (value == null) {
            throw new IllegalStateException(
                    "retrieval transaction returned no result"
            );
        }
        return value;
    }
}
