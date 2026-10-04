package kz.alimbetov.akmai.knowledge.projection;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies the configured retrieval transaction timeout to published read paths
 * without changing ingestion/write transaction semantics.
 */
@Component
@Primary
public class TimedPublishedSearchProjectionReader
        implements PublishedSearchProjectionReader {

    private final PostgresSearchProjectionRepository delegate;
    private final TransactionTemplate transactionTemplate;

    public TimedPublishedSearchProjectionReader(
            PostgresSearchProjectionRepository delegate,
            @Qualifier("retrievalTransactionTemplate")
            TransactionTemplate transactionTemplate
    ) {
        this.delegate = delegate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<SearchProjection> findByDocumentAndChunkIds(
            String documentId,
            List<String> chunkIds,
            Set<Long> accessLevels
    ) {
        return read(() -> delegate.findByDocumentAndChunkIds(
                documentId,
                chunkIds,
                accessLevels
        ));
    }

    @Override
    public List<SearchProjection> findByDocumentGenerationAndChunkIds(
            String documentId,
            long generation,
            List<String> chunkIds,
            Set<Long> accessLevels
    ) {
        return read(() -> delegate.findByDocumentGenerationAndChunkIds(
                documentId,
                generation,
                chunkIds,
                accessLevels
        ));
    }

    @Override
    public List<SearchProjection> findPublishedByKeys(
            List<ProjectionKey> keys,
            Set<Long> accessLevels
    ) {
        return read(() -> delegate.findPublishedByKeys(
                keys,
                accessLevels
        ));
    }

    @Override
    public List<SearchProjection> findAdjacent(
            String documentId,
            long generation,
            int chunkIndex,
            int radius,
            Set<Long> accessLevels
    ) {
        return read(() -> delegate.findAdjacent(
                documentId,
                generation,
                chunkIndex,
                radius,
                accessLevels
        ));
    }

    @Override
    public List<SearchProjection> searchLexical(
            String query,
            String language,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        return read(() -> delegate.searchLexical(
                query,
                language,
                documentIds,
                accessLevels,
                limit
        ));
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
