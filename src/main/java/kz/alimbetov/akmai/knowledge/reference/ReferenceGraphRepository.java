package kz.alimbetov.akmai.knowledge.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.chunking.CrossReferenceExtractor;
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

        List<TargetRow> targets = new ArrayList<>();
        List<EdgeRow> edges = new ArrayList<>();

        for (SearchProjection projection : projections) {
            extractor.extractAnchor(projection.text()).ifPresent(anchor ->
                    targets.add(new TargetRow(projection, anchor))
            );
            for (CrossReference reference : extractor.extractTyped(projection.text())) {
                edges.add(new EdgeRow(projection, reference));
            }
        }

        if (!targets.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    """
                    INSERT INTO knowledge_reference_target (
                        document_id, generation, chunk_id, reference_type,
                        canonical_value, raw_value, language
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (
                        document_id, generation, reference_type, canonical_value
                    ) DO UPDATE SET
                        chunk_id = EXCLUDED.chunk_id,
                        raw_value = EXCLUDED.raw_value,
                        language = EXCLUDED.language
                    """,
                    targets,
                    100,
                    (ps, row) -> {
                        ps.setString(1, row.projection().documentId());
                        ps.setLong(2, row.projection().generation());
                        ps.setString(3, row.projection().chunkId());
                        ps.setString(4, row.anchor().type().name());
                        ps.setString(5, row.anchor().canonicalValue());
                        ps.setString(6, row.anchor().rawValue());
                        ps.setString(7, row.anchor().language());
                    }
            );
        }

        if (!edges.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    """
                    INSERT INTO knowledge_reference_edge (
                        document_id, generation, source_chunk_id,
                        reference_type, canonical_value, raw_value, language,
                        target_scope, target_document_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    edges,
                    100,
                    (ps, row) -> {
                        ps.setString(1, row.projection().documentId());
                        ps.setLong(2, row.projection().generation());
                        ps.setString(3, row.projection().chunkId());
                        ps.setString(4, row.reference().type().name());
                        ps.setString(5, row.reference().canonicalValue());
                        ps.setString(6, row.reference().rawValue());
                        ps.setString(7, row.reference().language());
                        ps.setString(8, row.reference().targetScope().name());
                        ps.setString(9, row.reference().targetDocumentId());
                    }
            );
        }
    }

    public List<String> resolveSameDocumentTargets(
            String documentId,
            List<String> sourceChunkIds,
            int limit
    ) {
        return resolveSameDocumentTargets(
                documentId,
                sourceChunkIds,
                Set.of(),
                limit
        );
    }

    public List<String> resolveSameDocumentTargets(
            String documentId,
            List<String> sourceChunkIds,
            Set<Long> accessLevels,
            int limit
    ) {
        if (sourceChunkIds == null
                || sourceChunkIds.isEmpty()
                || accessLevels == null
                || accessLevels.isEmpty()
                || limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT DISTINCT t.chunk_id
                FROM knowledge_reference_edge e
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = e.document_id
                 AND l.published_generation = e.generation
                JOIN knowledge_reference_target t
                  ON t.document_id = e.document_id
                 AND t.generation = e.generation
                 AND t.reference_type = e.reference_type
                 AND t.canonical_value = e.canonical_value
                WHERE e.document_id = ?
                  AND e.source_chunk_id = ANY (?)
                  AND l.access_level = ANY (?)
                  AND e.target_scope = 'SAME_DOCUMENT'
                  AND l.retention_status = 'ACTIVE'
                  AND t.chunk_id <> ALL (?)
                ORDER BY t.chunk_id
                LIMIT ?
                """,
                ps -> {
                    ps.setString(1, documentId);
                    var sourceArray = ps.getConnection().createArrayOf(
                            "varchar",
                            sourceChunkIds.toArray()
                    );
                    ps.setArray(2, sourceArray);
                    ps.setArray(
                            3,
                            ps.getConnection().createArrayOf(
                                    "bigint",
                                    accessLevels.toArray()
                            )
                    );
                    ps.setArray(4, sourceArray);
                    ps.setInt(5, limit);
                },
                (rs, rowNum) -> rs.getString(1)
        );
    }

    public void cloneGeneration(
            String documentId,
            long sourceGeneration,
            long targetGeneration
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_reference_target (
                    document_id, generation, chunk_id, reference_type,
                    canonical_value, raw_value, language
                )
                SELECT document_id, ?, chunk_id, reference_type,
                       canonical_value, raw_value, language
                FROM knowledge_reference_target
                WHERE document_id = ?
                  AND generation = ?
                ON CONFLICT DO NOTHING
                """,
                targetGeneration,
                documentId,
                sourceGeneration
        );
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_reference_edge (
                    document_id, generation, source_chunk_id,
                    reference_type, canonical_value, raw_value, language,
                    target_scope, target_document_id
                )
                SELECT document_id, ?, source_chunk_id,
                       reference_type, canonical_value, raw_value, language,
                       target_scope, target_document_id
                FROM knowledge_reference_edge
                WHERE document_id = ?
                  AND generation = ?
                ON CONFLICT DO NOTHING
                """,
                targetGeneration,
                documentId,
                sourceGeneration
        );
    }

    public void deleteGeneration(String documentId, long generation) {
        jdbcTemplate.update(
                """
                DELETE FROM knowledge_reference_edge
                WHERE document_id = ? AND generation = ?
                """,
                documentId,
                generation
        );
        jdbcTemplate.update(
                """
                DELETE FROM knowledge_reference_target
                WHERE document_id = ? AND generation = ?
                """,
                documentId,
                generation
        );
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
