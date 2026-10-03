package kz.alimbetov.akmai.knowledge.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.chunking.CrossReferenceExtractor;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReferenceGraphRepository {

    private final JdbcTemplate jdbcTemplate;
    private final CrossReferenceExtractor extractor;

    public ReferenceGraphRepository(
            JdbcTemplate jdbcTemplate,
            CrossReferenceExtractor extractor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.extractor = extractor;
    }

    public void saveAll(List<SearchProjection> projections) {
        if (projections == null || projections.isEmpty()) {
            return;
        }

        projections.stream()
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
            List<SearchProjection> projections
    ) {
        requireGenerationIdentity(identity, projections);
        if (projections == null || projections.isEmpty()) {
            return;
        }

        List<TargetRow> targets = new ArrayList<>();
        List<EdgeRow> edges = new ArrayList<>();

        for (SearchProjection projection : projections) {
            extractor.extractAnchor(projection.text()).ifPresent(anchor ->
                    targets.add(new TargetRow(projection, anchor))
            );
            for (CrossReference reference
                    : extractor.extractTyped(projection.text())) {
                edges.add(new EdgeRow(projection, reference));
            }
        }

        if (!targets.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    """
                    INSERT INTO knowledge_reference_target (
                        access_level,
                        document_id,
                        generation,
                        chunk_id,
                        reference_type,
                        canonical_value,
                        raw_value,
                        language
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (
                        access_level,
                        document_id,
                        generation,
                        reference_type,
                        canonical_value
                    ) DO UPDATE SET
                        chunk_id = EXCLUDED.chunk_id,
                        raw_value = EXCLUDED.raw_value,
                        language = EXCLUDED.language
                    """,
                    targets,
                    100,
                    (ps, row) -> {
                        ps.setLong(1, identity.accessLevel());
                        ps.setString(2, identity.documentId());
                        ps.setLong(3, identity.generation());
                        ps.setString(4, row.projection().chunkId());
                        ps.setString(5, row.anchor().type().name());
                        ps.setString(6, row.anchor().canonicalValue());
                        ps.setString(7, row.anchor().rawValue());
                        ps.setString(8, row.anchor().language());
                    }
            );
        }

        if (!edges.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    """
                    INSERT INTO knowledge_reference_edge (
                        access_level,
                        document_id,
                        generation,
                        source_chunk_id,
                        reference_type,
                        canonical_value,
                        raw_value,
                        language,
                        target_scope,
                        target_document_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    edges,
                    100,
                    (ps, row) -> {
                        ps.setLong(1, identity.accessLevel());
                        ps.setString(2, identity.documentId());
                        ps.setLong(3, identity.generation());
                        ps.setString(4, row.projection().chunkId());
                        ps.setString(5, row.reference().type().name());
                        ps.setString(6, row.reference().canonicalValue());
                        ps.setString(7, row.reference().rawValue());
                        ps.setString(8, row.reference().language());
                        ps.setString(9, row.reference().targetScope().name());
                        ps.setString(
                                10,
                                row.reference().targetDocumentId()
                        );
                    }
            );
        }
    }

    public List<String> resolveSameDocumentTargets(
            String documentId,
            List<String> sourceChunkIds,
            Set<Long> accessLevels,
            int limit
    ) {
        if (sourceChunkIds == null
                || sourceChunkIds.isEmpty()
                || limit <= 0) {
            requireAccessLevels(accessLevels);
            return List.of();
        }

        java.util.LinkedHashSet<String> result =
                new java.util.LinkedHashSet<>();
        for (long accessLevel : routedAccessLevels(accessLevels)) {
            result.addAll(resolveSameDocumentTargets(
                    documentId,
                    null,
                    sourceChunkIds,
                    accessLevel,
                    limit
            ));
        }

        return result.stream()
                .sorted()
                .limit(limit)
                .toList();
    }

    public List<String> resolveSameDocumentTargets(
            String documentId,
            long generation,
            List<String> sourceChunkIds,
            Set<Long> accessLevels,
            int limit
    ) {
        if (generation <= 0
                || sourceChunkIds == null
                || sourceChunkIds.isEmpty()
                || limit <= 0) {
            requireAccessLevels(accessLevels);
            return List.of();
        }

        java.util.LinkedHashSet<String> result =
                new java.util.LinkedHashSet<>();
        for (long accessLevel : routedAccessLevels(accessLevels)) {
            result.addAll(resolveSameDocumentTargets(
                    documentId,
                    generation,
                    sourceChunkIds,
                    accessLevel,
                    limit
            ));
        }

        return result.stream()
                .sorted()
                .limit(limit)
                .toList();
    }

    private List<String> resolveSameDocumentTargets(
            String documentId,
            Long generation,
            List<String> sourceChunkIds,
            long accessLevel,
            int limit
    ) {
        String generationPredicate = generation == null
                ? ""
                : " AND e.generation = ? ";

        String sql = """
                SELECT DISTINCT t.chunk_id
                FROM knowledge_reference_edge e
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = e.document_id
                 AND l.published_generation = e.generation
                 AND l.access_level = e.access_level
                JOIN knowledge_reference_target t
                  ON t.access_level = e.access_level
                 AND t.document_id = e.document_id
                 AND t.generation = e.generation
                 AND t.reference_type = e.reference_type
                 AND t.canonical_value = e.canonical_value
                WHERE e.access_level = ?
                  AND e.document_id = ?
                """ + generationPredicate + """
                  AND e.source_chunk_id = ANY (?)
                  AND e.target_scope = 'SAME_DOCUMENT'
                  AND l.retention_status = 'ACTIVE'
                  AND t.chunk_id <> ALL (?)
                ORDER BY t.chunk_id
                LIMIT ?
                """;

        return jdbcTemplate.query(
                sql,
                ps -> {
                    int index = 1;
                    ps.setLong(index++, accessLevel);
                    ps.setString(index++, documentId);
                    if (generation != null) {
                        ps.setLong(index++, generation);
                    }
                    var sourceArray = ps.getConnection().createArrayOf(
                            "varchar",
                            sourceChunkIds.toArray()
                    );
                    ps.setArray(index++, sourceArray);
                    ps.setArray(index++, sourceArray);
                    ps.setInt(index, limit);
                },
                (rs, rowNum) -> rs.getString(1)
        );
    }

    private List<Long> routedAccessLevels(Set<Long> accessLevels) {
        requireAccessLevels(accessLevels);
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

    private void requireAccessLevels(Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    "accessLevels must not be empty"
            );
        }
    }

    public void cloneGeneration(
            GenerationIdentity source,
            GenerationIdentity target
    ) {
        if (source == null || target == null) {
            throw new IllegalArgumentException(
                    "source and target must not be null"
            );
        }
        if (!source.documentId().equals(target.documentId())) {
            throw new IllegalArgumentException(
                    "Reference clone must stay within one document"
            );
        }
        if (source.accessLevel() != target.accessLevel()) {
            throw new IllegalArgumentException(
                    "Reference clone cannot cross access levels"
            );
        }

        jdbcTemplate.update(
                """
                INSERT INTO knowledge_reference_target (
                    access_level,
                    document_id,
                    generation,
                    chunk_id,
                    reference_type,
                    canonical_value,
                    raw_value,
                    language
                )
                SELECT access_level,
                       document_id,
                       ?,
                       chunk_id,
                       reference_type,
                       canonical_value,
                       raw_value,
                       language
                FROM knowledge_reference_target
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                ON CONFLICT DO NOTHING
                """,
                target.generation(),
                source.accessLevel(),
                source.documentId(),
                source.generation()
        );

        jdbcTemplate.update(
                """
                INSERT INTO knowledge_reference_edge (
                    access_level,
                    document_id,
                    generation,
                    source_chunk_id,
                    reference_type,
                    canonical_value,
                    raw_value,
                    language,
                    target_scope,
                    target_document_id
                )
                SELECT access_level,
                       document_id,
                       ?,
                       source_chunk_id,
                       reference_type,
                       canonical_value,
                       raw_value,
                       language,
                       target_scope,
                       target_document_id
                FROM knowledge_reference_edge
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                ON CONFLICT DO NOTHING
                """,
                target.generation(),
                source.accessLevel(),
                source.documentId(),
                source.generation()
        );
    }

    public void cloneGeneration(
            String documentId,
            long sourceGeneration,
            long targetGeneration
    ) {
        cloneGeneration(
                identityFor(documentId, sourceGeneration),
                identityFor(documentId, targetGeneration)
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
                DELETE FROM knowledge_reference_edge
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                """,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
        );
        jdbcTemplate.update(
                """
                DELETE FROM knowledge_reference_target
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                """,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
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
            List<SearchProjection> projections
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        if (projections == null || projections.isEmpty()) {
            return;
        }
        boolean mismatch = projections.stream().anyMatch(
                projection -> !identity.documentId().equals(
                                projection.documentId()
                        )
                        || identity.generation()
                                != projection.generation()
                        || (
                            projection.accessLevel() > 0
                            && identity.accessLevel()
                                    != projection.accessLevel()
                        )
        );
        if (mismatch) {
            throw new IllegalArgumentException(
                    "All reference projections must belong to generation identity"
            );
        }
    }

    private record GenerationKey(
            String documentId,
            long generation
    ) {
    }

    private record TargetRow(
            SearchProjection projection,
            StructuralAnchor anchor
    ) {
    }

    private record EdgeRow(
            SearchProjection projection,
            CrossReference reference
    ) {
    }
}
