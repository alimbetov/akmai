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
    public void deleteByDocumentId(String documentId) {
        jdbcTemplate.update(
                "DELETE FROM document_identifier WHERE document_id = ?",
                documentId
        );
    }

    @Override
    public void rebuild() {
        // PostgreSQL is the canonical store and the initial search implementation.
        // A future local index (for example Lucene) will rebuild from this table.
    }
}
