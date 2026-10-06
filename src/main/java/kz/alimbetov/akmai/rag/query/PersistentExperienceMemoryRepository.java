package kz.alimbetov.akmai.rag.query;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PersistentExperienceMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public PersistentExperienceMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MemoryEntry> find(
            String embeddingProfileId,
            Set<Long> accessLevels,
            float[] queryVector,
            double similarityThreshold,
            int limit
    ) {
        String scopeKey = scopeKey(accessLevels);
        String vector = vectorLiteral(queryVector);
        List<MemoryEntry> entries = jdbcTemplate.query(
                """
                SELECT m.id,
                       m.normalized_question,
                       m.grounded_answer,
                       m.observed_at,
                       1.0 - (m.embedding <=> ?::vector) AS similarity
                FROM rag_experience_memory m
                WHERE m.embedding_profile_id = ?
                  AND m.scope_key = ?
                  AND 1.0 - (m.embedding <=> ?::vector) >= ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM rag_experience_memory_source s
                      LEFT JOIN knowledge_document_generation g
                        ON g.document_id = s.document_id
                       AND g.generation = s.generation
                       AND g.access_level = s.access_level
                       AND g.generation_status = 'PUBLISHED'
                      WHERE s.memory_id = m.id
                        AND g.document_id IS NULL
                  )
                ORDER BY m.embedding <=> ?::vector
                LIMIT ?
                """,
                (rs, rowNum) -> new MemoryEntry(
                        rs.getObject("id", UUID.class),
                        rs.getDouble("similarity"),
                        rs.getString("normalized_question"),
                        rs.getString("grounded_answer"),
                        List.of(),
                        rs.getTimestamp("observed_at").toInstant()
                ),
                vector,
                embeddingProfileId,
                scopeKey,
                vector,
                similarityThreshold,
                vector,
                limit
        );
        if (entries.isEmpty()) {
            return List.of();
        }

        ArrayList<MemoryEntry> hydrated = new ArrayList<>(entries.size());
        for (MemoryEntry entry : entries) {
            List<SourceKey> sources = jdbcTemplate.query(
                    """
                    SELECT access_level, document_id, generation, chunk_id
                    FROM rag_experience_memory_source
                    WHERE memory_id = ?
                    ORDER BY access_level, document_id, generation, chunk_id
                    """,
                    (rs, rowNum) -> new SourceKey(
                            rs.getLong("access_level"),
                            rs.getString("document_id"),
                            rs.getLong("generation"),
                            rs.getString("chunk_id")
                    ),
                    entry.id()
            );
            hydrated.add(new MemoryEntry(
                    entry.id(),
                    entry.similarity(),
                    entry.normalizedQuestion(),
                    entry.groundedAnswer(),
                    sources,
                    entry.observedAt()
            ));
        }
        return List.copyOf(hydrated);
    }

    @Transactional
    public void record(
            String embeddingProfileId,
            Set<Long> accessLevels,
            float[] vector,
            String normalizedQuestion,
            String groundedAnswer,
            List<SourceKey> sources,
            Instant observedAt
    ) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO rag_experience_memory (
                    id, embedding_profile_id, scope_key,
                    normalized_question, grounded_answer,
                    embedding, observed_at
                ) VALUES (?, ?, ?, ?, ?, ?::vector, ?)
                """,
                id,
                embeddingProfileId,
                scopeKey(accessLevels),
                normalizedQuestion,
                groundedAnswer,
                vectorLiteral(vector),
                Timestamp.from(observedAt)
        );
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO rag_experience_memory_source (
                    memory_id, access_level, document_id, generation, chunk_id
                ) VALUES (?, ?, ?, ?, ?)
                """,
                sources,
                Math.min(64, sources.size()),
                (ps, source) -> {
                    ps.setObject(1, id);
                    ps.setLong(2, source.accessLevel());
                    ps.setString(3, source.documentId());
                    ps.setLong(4, source.generation());
                    ps.setString(5, source.chunkId());
                }
        );
    }

    public boolean sourcesStillPublished(List<SourceKey> sources) {
        if (sources == null || sources.isEmpty()) {
            return false;
        }
        for (SourceKey source : sources) {
            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*)
                    FROM knowledge_document_generation
                    WHERE document_id = ?
                      AND generation = ?
                      AND access_level = ?
                      AND generation_status = 'PUBLISHED'
                    """,
                    Integer.class,
                    source.documentId(),
                    source.generation(),
                    source.accessLevel()
            );
            if (count == null || count != 1) {
                return false;
            }
        }
        return true;
    }

    static String scopeKey(Set<Long> accessLevels) {
        if (accessLevels == null || accessLevels.isEmpty()) {
            throw new IllegalArgumentException("accessLevels must not be empty");
        }
        return accessLevels.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    static String vectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        StringBuilder value = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) {
                throw new IllegalArgumentException("vector must be finite");
            }
            if (i > 0) {
                value.append(',');
            }
            value.append(Float.toString(vector[i]));
        }
        return value.append(']').toString();
    }

    public record SourceKey(
            long accessLevel,
            String documentId,
            long generation,
            String chunkId
    ) {
    }

    public record MemoryEntry(
            UUID id,
            double similarity,
            String normalizedQuestion,
            String groundedAnswer,
            List<SourceKey> sources,
            Instant observedAt
    ) {
        public MemoryEntry {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }
}
