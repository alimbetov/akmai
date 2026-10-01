package kz.alimbetov.akmai.knowledge.identifier;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentIdentifierRepository {

    private final JdbcTemplate jdbcTemplate;

    public DocumentIdentifierRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(List<DocumentIdentifier> identifiers) {
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO document_identifier (
                    document_id, chunk_id, page_number, identifier_type,
                    raw_value, normalized_value, context_text, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                identifiers,
                100,
                (ps, value) -> {
                    ps.setString(1, value.documentId());
                    ps.setString(2, value.chunkId());
                    ps.setInt(3, value.pageNumber());
                    ps.setString(4, value.type().name());
                    ps.setString(5, value.rawValue());
                    ps.setString(6, value.normalizedValue());
                    ps.setString(7, value.contextText());
                    ps.setTimestamp(8, Timestamp.from(value.createdAt()));
                }
        );
    }

    public List<DocumentIdentifier> findExact(
            IdentifierType type,
            String normalizedValue,
            int limit
    ) {
        return jdbcTemplate.query(
                """
                SELECT document_id, chunk_id, page_number, identifier_type,
                       raw_value, normalized_value, context_text, created_at
                  FROM document_identifier
                 WHERE identifier_type = ?
                   AND normalized_value = ?
                 ORDER BY created_at DESC
                 LIMIT ?
                """,
                (rs, rowNum) -> new DocumentIdentifier(
                        rs.getString("document_id"),
                        rs.getString("chunk_id"),
                        rs.getInt("page_number"),
                        IdentifierType.valueOf(rs.getString("identifier_type")),
                        rs.getString("raw_value"),
                        rs.getString("normalized_value"),
                        rs.getString("context_text"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                type.name(),
                normalizedValue,
                limit
        );
    }

    public List<DocumentIdentifier> findExact(String normalizedValue, int limit) {
        return jdbcTemplate.query(
                """
                SELECT document_id, chunk_id, page_number, identifier_type,
                       raw_value, normalized_value, context_text, created_at
                  FROM document_identifier
                 WHERE normalized_value = ?
                 ORDER BY created_at DESC
                 LIMIT ?
                """,
                (rs, rowNum) -> new DocumentIdentifier(
                        rs.getString("document_id"),
                        rs.getString("chunk_id"),
                        rs.getInt("page_number"),
                        IdentifierType.valueOf(rs.getString("identifier_type")),
                        rs.getString("raw_value"),
                        rs.getString("normalized_value"),
                        rs.getString("context_text"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                normalizedValue,
                limit
        );
    }
}
