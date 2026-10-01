package kz.alimbetov.akmai.knowledge.identifier.search;

import java.util.List;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierNormalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PostgresIdentifierSearchIndex implements IdentifierSearchIndex {

    private final DocumentIdentifierRepository repository;
    private final IdentifierNormalizer normalizer;
    private final JdbcTemplate jdbcTemplate;

    public PostgresIdentifierSearchIndex(
            DocumentIdentifierRepository repository,
            IdentifierNormalizer normalizer,
            JdbcTemplate jdbcTemplate
    ) {
        this.repository = repository;
        this.normalizer = normalizer;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void index(List<DocumentIdentifier> identifiers) {
        repository.saveAll(identifiers);
    }

    @Override
    public List<DocumentIdentifier> search(String query, int limit) {
        return repository.findExact(normalizer.normalize(query), limit);
    }

    @Override
    public List<DocumentIdentifier> search(IdentifierSearchQuery query) {
        String normalized = normalizer.normalize(query.normalizedValue());
        if (query.limit() <= 0 || query.limit() > 100) {
            throw new IllegalArgumentException("Identifier search limit must be between 1 and 100");
        }
        return switch (query.matchMode()) {
            case EXACT -> repository.findExact(query.type(), normalized, query.limit());
            case PREFIX -> repository.findPrefix(query.type(), normalized, query.limit());
            case PARTIAL -> repository.findPartial(query.type(), normalized, query.limit());
        };
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        jdbcTemplate.update(
                "DELETE FROM document_identifier WHERE document_id = ?",
                documentId
        );
    }

}
