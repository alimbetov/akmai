package kz.alimbetov.akmai.knowledge.identifier;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentIdentifierRepository {

    private final JdbcTemplate jdbcTemplate;

    public DocumentIdentifierRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(List<DocumentIdentifier> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO document_identifier (
                    document_id, generation, chunk_id, page_number, identifier_type,
                    raw_value, normalized_value, context_text, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    document_id, generation, chunk_id, identifier_type, normalized_value
                )
                DO UPDATE SET
                    raw_value = EXCLUDED.raw_value,
                    context_text = EXCLUDED.context_text,
                    page_number = EXCLUDED.page_number,
                    created_at = EXCLUDED.created_at
                """,
                identifiers,
                100,
                (ps, value) -> {
                    ps.setString(1, value.documentId());
                    ps.setLong(2, value.generation());
                    ps.setString(3, value.chunkId());
                    ps.setInt(4, value.pageNumber());
                    ps.setString(5, value.type().name());
                    ps.setString(6, value.rawValue());
                    ps.setString(7, value.normalizedValue());
                    ps.setString(8, value.contextText());
                    ps.setTimestamp(9, Timestamp.from(value.createdAt()));
                }
        );
    }

    public void saveAll(
            GenerationIdentity identity,
            List<DocumentIdentifier> identifiers
    ) {
        requireGenerationIdentity(identity, identifiers);
        saveAll(identifiers);
    }

    public List<DocumentIdentifier> findExact(
            IdentifierType type,
            String normalizedValue,
            Set<Long> accessLevels,
            int limit
    ) {
        return queryPublishedScoped(
                " AND i.identifier_type = ? AND i.normalized_value = ? ",
                accessLevels,
                limit,
                type.name(),
                normalizedValue
        );
    }

    public List<DocumentIdentifier> findPrefix(
            IdentifierType type,
            String normalizedPrefix,
            Set<Long> accessLevels,
            int limit
    ) {
        return findLikeScoped(
                type,
                escapeLike(normalizedPrefix) + "%",
                accessLevels,
                limit
        );
    }

    public List<DocumentIdentifier> findPartial(
            IdentifierType type,
            String normalizedPart,
            Set<Long> accessLevels,
            int limit
    ) {
        return findLikeScoped(
                type,
                "%" + escapeLike(normalizedPart) + "%",
                accessLevels,
                limit
        );
    }

    public List<DocumentIdentifier> findExact(
            String normalizedValue,
            Set<Long> accessLevels,
            int limit
    ) {
        return queryPublishedScoped(
                " AND i.normalized_value = ? ",
                accessLevels,
                limit,
                normalizedValue
        );
    }

    public List<DocumentIdentifier> findGeneration(
            String documentId,
            long generation
    ) {
        return jdbcTemplate.query(
                """
                SELECT document_id, generation, chunk_id, page_number,
                       identifier_type, raw_value, normalized_value,
                       context_text, created_at
                FROM document_identifier
                WHERE document_id = ?
                  AND generation = ?
                ORDER BY id
                """,
                this::map,
                documentId,
                generation
        );
    }

    public List<DocumentIdentifier> findGeneration(
            GenerationIdentity identity
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        return findGeneration(
                identity.documentId(),
                identity.generation()
        );
    }

    public void deleteGeneration(String documentId, long generation) {
        jdbcTemplate.update(
                """
                DELETE FROM document_identifier
                WHERE document_id = ?
                  AND generation = ?
                """,
                documentId,
                generation
        );
    }

    public void deleteGeneration(GenerationIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        deleteGeneration(
                identity.documentId(),
                identity.generation()
        );
    }

    public void deleteDocument(String documentId) {
        jdbcTemplate.update(
                "DELETE FROM document_identifier WHERE document_id = ?",
                documentId
        );
    }

    private void requireGenerationIdentity(
            GenerationIdentity identity,
            List<DocumentIdentifier> identifiers
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        if (identifiers == null || identifiers.isEmpty()) {
            return;
        }
        boolean mismatch = identifiers.stream().anyMatch(
                identifier -> !identity.documentId().equals(
                                identifier.documentId()
                        )
                        || identity.generation()
                                != identifier.generation()
        );
        if (mismatch) {
            throw new IllegalArgumentException(
                    "All identifiers must belong to generation identity"
            );
        }
    }

    private List<DocumentIdentifier> findLikeScoped(
            IdentifierType type,
            String pattern,
            Set<Long> accessLevels,
            int limit
    ) {
        requireAccessLevels(accessLevels);
        return jdbcTemplate.query(
                """
                SELECT i.document_id, i.generation, i.chunk_id, i.page_number,
                       i.identifier_type, i.raw_value, i.normalized_value,
                       i.context_text, i.created_at
                FROM document_identifier i
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = i.document_id
                 AND l.published_generation = i.generation
                WHERE l.retention_status = 'ACTIVE'
                  AND l.access_level = ANY (?)
                  AND i.identifier_type = ?
                  AND i.normalized_value LIKE ? ESCAPE '\\'
                ORDER BY i.created_at DESC
                LIMIT ?
                """,
                ps -> {
                    ps.setArray(
                            1,
                            ps.getConnection().createArrayOf(
                                    "bigint",
                                    accessLevels.toArray()
                            )
                    );
                    ps.setString(2, type.name());
                    ps.setString(3, pattern);
                    ps.setInt(4, limit);
                },
                this::map
        );
    }

    private List<DocumentIdentifier> queryPublishedScoped(
            String predicate,
            Set<Long> accessLevels,
            int limit,
            String... arguments
    ) {
        requireAccessLevels(accessLevels);
        return jdbcTemplate.query(
                """
                SELECT i.document_id, i.generation, i.chunk_id, i.page_number,
                       i.identifier_type, i.raw_value, i.normalized_value,
                       i.context_text, i.created_at
                FROM document_identifier i
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = i.document_id
                 AND l.published_generation = i.generation
                WHERE l.retention_status = 'ACTIVE'
                  AND l.access_level = ANY (?)
                """ + predicate + """
                ORDER BY i.created_at DESC
                LIMIT ?
                """,
                ps -> {
                    int index = 1;
                    ps.setArray(
                            index++,
                            ps.getConnection().createArrayOf(
                                    "bigint",
                                    accessLevels.toArray()
                            )
                    );
                    for (String argument : arguments) {
                        ps.setString(index++, argument);
                    }
                    ps.setInt(index, limit);
                },
                this::map
        );
    }

    private void requireAccessLevels(Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }
    }

    private DocumentIdentifier map(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentIdentifier(
                rs.getString("document_id"),
                rs.getLong("generation"),
                rs.getString("chunk_id"),
                rs.getInt("page_number"),
                IdentifierType.valueOf(rs.getString("identifier_type")),
                rs.getString("raw_value"),
                rs.getString("normalized_value"),
                rs.getString("context_text"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
