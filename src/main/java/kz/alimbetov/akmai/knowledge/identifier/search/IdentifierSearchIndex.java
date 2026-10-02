package kz.alimbetov.akmai.knowledge.identifier.search;

import java.util.List;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;

public interface IdentifierSearchIndex {

    void index(List<DocumentIdentifier> identifiers);

    List<DocumentIdentifier> search(IdentifierSearchQuery query);

    void deleteByDocumentId(String documentId);

    void deleteGeneration(String documentId, long generation);
}
