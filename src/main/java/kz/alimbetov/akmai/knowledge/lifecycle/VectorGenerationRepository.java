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
        if (entries.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO knowledge_document_vector_generation (
                    document_id, generation, vector_id, chunk_id
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (document_id, generation, vector_id) DO NOTHING
                """,
                entries,
                100,
                (ps, entry) -> {
                    ps.setString(1, documentId);
                    ps.setLong(2, generation);
                    ps.setString(3, entry.vectorId());
                    ps.setString(4, entry.chunkId());
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
