package kz.alimbetov.akmai.knowledge.reference;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Primary
public class TimedReferenceGraphLookupReader
        implements ReferenceGraphLookupReader {

    private final ReferenceGraphRepository delegate;
    private final TransactionTemplate transactionTemplate;

    public TimedReferenceGraphLookupReader(
            ReferenceGraphRepository delegate,
            @Qualifier("retrievalTransactionTemplate")
            TransactionTemplate transactionTemplate
    ) {
        this.delegate = delegate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<String> resolveSameDocumentTargets(
            String documentId,
            long generation,
            List<String> sourceChunkIds,
            Set<Long> accessLevels,
            int limit
    ) {
        List<String> value = transactionTemplate.execute(status ->
                delegate.resolveSameDocumentTargets(
                        documentId,
                        generation,
                        sourceChunkIds,
                        accessLevels,
                        limit
                )
        );
        if (value == null) {
            throw new IllegalStateException(
                    "reference retrieval transaction returned no result"
            );
        }
        return value;
    }
}
