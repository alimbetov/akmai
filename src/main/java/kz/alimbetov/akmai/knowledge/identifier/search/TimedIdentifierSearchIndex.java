package kz.alimbetov.akmai.knowledge.identifier.search;

import java.util.List;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps identifier retrieval under the same bounded DB timeout contract as
 * vector and projection reads while leaving indexing writes unchanged.
 */
@Component
@Primary
public class TimedIdentifierSearchIndex implements IdentifierSearchIndex {

    private final PostgresIdentifierSearchIndex delegate;
    private final TransactionTemplate transactionTemplate;

    public TimedIdentifierSearchIndex(
            PostgresIdentifierSearchIndex delegate,
            @Qualifier("retrievalTransactionTemplate")
            TransactionTemplate transactionTemplate
    ) {
        this.delegate = delegate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void index(List<DocumentIdentifier> identifiers) {
        delegate.index(identifiers);
    }

    @Override
    public List<DocumentIdentifier> search(IdentifierSearchQuery query) {
        List<DocumentIdentifier> value = transactionTemplate.execute(
                status -> delegate.search(query)
        );
        if (value == null) {
            throw new IllegalStateException(
                    "identifier retrieval transaction returned no result"
            );
        }
        return value;
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        delegate.deleteByDocumentId(documentId);
    }

    @Override
    public void deleteGeneration(String documentId, long generation) {
        delegate.deleteGeneration(documentId, generation);
    }
}
