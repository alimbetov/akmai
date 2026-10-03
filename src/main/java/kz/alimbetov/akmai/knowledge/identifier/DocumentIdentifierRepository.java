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

        identifiers.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        value -> new GenerationKey(
                                value.documentId(),
                                value.generation()
                        ),
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()
                ))
                .forEach((key, values) ->
                        saveAll(
                                identityFor(
                                        key.documentId(),
                                        key.generation()
                                ),
                                values
                        )
                );
    }

    public void saveAll(
            GenerationIdentity identity,
            List<DocumentIdentifier> identifiers
    ) {
        requireGenerationIdentity(identity, identifiers);
        if (identifiers == null || identifiers.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                """
                INSERT INTO document_identifier (
                    access_level,
                    document_id,
                    generation,
                    chunk_id,
                    page_number,
                    identifier_type,
                    raw_value,
                    normalized_value,
                    context_text,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    access_level,
                    document_id,
                    generation,
                    chunk_id,
                    identifier_type,
                    normalized_value
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
                    ps.setLong(1, identity.accessLevel());
                    ps.setString(2, identity.documentId());
                    ps.setLong(3, identity.generation());
                    ps.setString(4, value.chunkId());
                    ps.setInt(5, value.pageNumber());
                    ps.setString(6, value.type().name());
                    ps.setString(7, value.rawValue());
                    ps.setString(8, value.normalizedValue());
                    ps.setString(9, value.contextText());
                    ps.setTimestamp(
                            10,
                            Timestamp.from(value.createdAt())
                    );
                }
        );
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
        return findGeneration(identityFor(documentId, generation));
    }

    public List<DocumentIdentifier> findGeneration(
            GenerationIdentity identity
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        return jdbcTemplate.query(
                """
                SELECT document_id, generation, chunk_id, page_number,
                       identifier_type, raw_value, normalized_value,
                       context_text, created_at
                FROM document_identifier
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                ORDER BY id
                """,
                this::map,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
        );
    }

    public void deleteGeneration(String documentId, long generation) {
        deleteGeneration(identityFor(documentId, generation));
    }

    public void deleteGeneration(GenerationIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        jdbcTemplate.update(
                """
                DELETE FROM document_identifier
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                """,
                identity.accessLevel(),
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

    private GenerationIdentity identityFor(
            String documentId,
            long generation
    ) {
        return jdbcTemplate.query(
                """
                SELECT access_level
                FROM knowledge_document_generation
                WHERE document_id = ?
                  AND generation = ?
                """,
                (rs, rowNum) -> new GenerationIdentity(
                        documentId,
                        generation,
                        rs.getLong("access_level")
                ),
                documentId,
                generation
        ).stream().findFirst().orElseThrow(() ->
                new IllegalStateException(
                        "Generation identity does not exist: "
                                + documentId
                                + "/"
                                + generation
                )
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
        return queryPublishedScoped(
                """
                 AND i.identifier_type = ?
                 AND i.normalized_value LIKE ? ESCAPE '\\'
                """,
                accessLevels,
                limit,
                type.name(),
                pattern
        );
    }

    private List<DocumentIdentifier> queryPublishedScoped(
            String predicate,
            Set<Long> accessLevels,
            int limit,
            String... arguments
    ) {
        List<Long> routed = routedAccessLevels(accessLevels);
        if (limit <= 0) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new java.util.ArrayList<>();

        for (int branch = 0; branch < routed.size(); branch++) {
            if (branch > 0) {
                sql.append("\nUNION ALL\n");
            }

            sql.append("""
                    (
                        SELECT i.document_id,
                               i.generation,
                               i.chunk_id,
                               i.page_number,
                               i.identifier_type,
                               i.raw_value,
                               i.normalized_value,
                               i.context_text,
                               i.created_at
                        FROM document_identifier i
                        JOIN knowledge_document_lifecycle l
                          ON l.document_id = i.document_id
                         AND l.published_generation = i.generation
                         AND l.access_level = i.access_level
                        WHERE l.retention_status = 'ACTIVE'
                          AND i.access_level = ?
                    """);
            sql.append(predicate);
            sql.append("""
                        ORDER BY i.created_at DESC
                        LIMIT ?
                    )
                    """);

            parameters.add(routed.get(branch));
            java.util.Collections.addAll(parameters, arguments);
            parameters.add(limit);
        }

        sql.insert(0, "SELECT * FROM (\n");
        sql.append("""
                ) scoped
                ORDER BY created_at DESC
                LIMIT ?
                """);
        parameters.add(limit);

        return jdbcTemplate.query(
                sql.toString(),
                ps -> {
                    for (int index = 0;
                            index < parameters.size();
                            index++) {
                        ps.setObject(index + 1, parameters.get(index));
                    }
                },
                this::map
        );
    }

    private List<Long> routedAccessLevels(Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }
        return accessLevels.stream()
                .peek(value -> {
                    if (value == null || value <= 0) {
                        throw new IllegalArgumentException(
                                "accessLevels must contain positive values"
                        );
                    }
                })
                .distinct()
                .sorted()
                .toList();
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

    private record GenerationKey(
            String documentId,
            long generation
    ) {
    }

    private String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
