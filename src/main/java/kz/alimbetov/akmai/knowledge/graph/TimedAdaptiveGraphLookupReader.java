package kz.alimbetov.akmai.knowledge.graph;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Primary
public class TimedAdaptiveGraphLookupReader
        implements AdaptiveGraphLookupReader {

    private final AdaptiveChunkGraphRepository delegate;
    private final TransactionTemplate transactionTemplate;

    public TimedAdaptiveGraphLookupReader(
            AdaptiveChunkGraphRepository delegate,
            @Qualifier("retrievalTransactionTemplate")
            TransactionTemplate transactionTemplate
    ) {
        this.delegate = delegate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<ChunkAssociation> findRelated(
            Set<Long> allowedAccessLevels,
            ChunkGraphNode source,
            int graphVersion,
            Set<AssociationBand> bands,
            double minimumWeight,
            int limit
    ) {
        List<ChunkAssociation> value = transactionTemplate.execute(status ->
                delegate.findRelated(
                        allowedAccessLevels,
                        source,
                        graphVersion,
                        bands,
                        minimumWeight,
                        limit
                )
        );
        if (value == null) {
            throw new IllegalStateException(
                    "adaptive graph lookup transaction returned no result"
            );
        }
        return value;
    }
}
