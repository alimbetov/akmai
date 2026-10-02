package kz.alimbetov.akmai.knowledge.identifier.search;

import java.util.List;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierNormalizer;
import org.springframework.stereotype.Component;

@Component
public class PostgresIdentifierSearchIndex implements IdentifierSearchIndex {

    private final DocumentIdentifierRepository repository;
    private final IdentifierNormalizer normalizer;

    public PostgresIdentifierSearchIndex(
            DocumentIdentifierRepository repository,
            IdentifierNormalizer normalizer
    ) {
        this.repository = repository;
        this.normalizer = normalizer;
    }

    @Override
    public void index(List<DocumentIdentifier> identifiers) {
        repository.saveAll(identifiers);
    }

    @Override
    public List<DocumentIdentifier> search(String query, int limit) {
        validateLimit(limit);
        String normalized = normalizer.normalize(query);
        return List.of();
    }

    @Override
    public List<DocumentIdentifier> search(IdentifierSearchQuery query) {
        validateLimit(query.limit());
        String normalized = normalizer.normalize(query.type(), query.normalizedValue());
        if (normalized.isBlank()
                || query.accessLevels() == null
                || query.accessLevels().isEmpty()) {
            return List.of();
        }
        return switch (query.matchMode()) {
            case EXACT -> repository.findExact(
                    query.type(),
                    normalized,
                    query.accessLevels(),
                    query.limit()
            );
            case PREFIX -> repository.findPrefix(
                    query.type(),
                    normalized,
                    query.accessLevels(),
                    query.limit()
            );
            case PARTIAL -> repository.findPartial(
                    query.type(),
                    normalized,
                    query.accessLevels(),
                    query.limit()
            );
        };
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        repository.deleteDocument(documentId);
    }

    @Override
    public void deleteGeneration(String documentId, long generation) {
        repository.deleteGeneration(documentId, generation);
    }

    private void validateLimit(int limit) {
        if (limit <= 0 || limit > 100) {
            throw new IllegalArgumentException(
                    "Identifier search limit must be between 1 and 100"
            );
        }
    }
}
