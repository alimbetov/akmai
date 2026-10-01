package kz.alimbetov.akmai.knowledge.lifecycle;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VectorGenerationRepository {

    private final JdbcTemplate jdbcTemplate;

    public VectorGenerationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(
            String documentId,
            long generation,
            List<VectorGenerationEntry> entries
    ) {
        save(documentId, generation, null, (short) 2, entries);
    }

    public void save(
            String documentId,
            long generation,
            String embeddingProfileId,
            short physicalIdVersion,
            List<VectorGenerationEntry> entries
    ) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO knowledge_document_vector_generation (
                    document_id, generation, vector_id, chunk_id,
                    embedding_profile_id, physical_id_version
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (document_id, generation, vector_id) DO NOTHING
                """,
                entries,
                100,
                (ps, entry) -> {
                    ps.setString(1, documentId);
                    ps.setLong(2, generation);
                    ps.setString(3, entry.vectorId());
                    ps.setString(4, entry.chunkId());
                    ps.setString(5, embeddingProfileId);
                    ps.setShort(6, physicalIdVersion);
                }
        );
    }

    public List<String> findVectorIds(String documentId, long generation) {
        return jdbcTemplate.queryForList(
                """
                SELECT vector_id
                FROM knowledge_document_vector_generation
                WHERE document_id = ?
                  AND generation = ?
                ORDER BY vector_id
                """,
                String.class,
                documentId,
                generation
        );
    }

    public String findEmbeddingProfileId(String documentId, long generation) {
        return jdbcTemplate.query(
                """
                SELECT embedding_profile_id
                FROM knowledge_document_vector_generation
                WHERE document_id = ?
                  AND generation = ?
                  AND embedding_profile_id IS NOT NULL
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getString(1),
                documentId,
                generation
        ).stream().findFirst().orElse(null);
    }

    public void deleteGeneration(String documentId, long generation) {
        jdbcTemplate.update(
                """
                DELETE FROM knowledge_document_vector_generation
                WHERE document_id = ?
                  AND generation = ?
                """,
                documentId,
                generation
        );
    }

    public record VectorGenerationEntry(String vectorId, String chunkId) {
    }
}
