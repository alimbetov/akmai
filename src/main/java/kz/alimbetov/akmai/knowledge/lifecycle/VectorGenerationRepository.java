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
            GenerationIdentity identity,
            List<VectorGenerationEntry> entries
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        save(identity, null, (short) 2, entries);
    }

    public void save(
            String documentId,
            long generation,
            List<VectorGenerationEntry> entries
    ) {
        save(
                identityFor(documentId, generation),
                null,
                (short) 2,
                entries
        );
    }

    public void save(
            GenerationIdentity identity,
            String embeddingProfileId,
            short physicalIdVersion,
            List<VectorGenerationEntry> entries
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        if (entries == null || entries.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                """
                INSERT INTO knowledge_document_vector_generation (
                    access_level,
                    document_id,
                    generation,
                    vector_id,
                    chunk_id,
                    embedding_profile_id,
                    physical_id_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    access_level,
                    document_id,
                    generation,
                    vector_id
                ) DO NOTHING
                """,
                entries,
                100,
                (ps, entry) -> {
                    ps.setLong(1, identity.accessLevel());
                    ps.setString(2, identity.documentId());
                    ps.setLong(3, identity.generation());
                    ps.setString(4, entry.vectorId());
                    ps.setString(5, entry.chunkId());
                    ps.setString(6, embeddingProfileId);
                    ps.setShort(7, physicalIdVersion);
                }
        );
    }

    public void save(
            String documentId,
            long generation,
            String embeddingProfileId,
            short physicalIdVersion,
            List<VectorGenerationEntry> entries
    ) {
        save(
                identityFor(documentId, generation),
                embeddingProfileId,
                physicalIdVersion,
                entries
        );
    }

    public List<String> findVectorIds(GenerationIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        return jdbcTemplate.queryForList(
                """
                SELECT vector_id
                FROM knowledge_document_vector_generation
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                ORDER BY vector_id
                """,
                String.class,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
        );
    }

    public List<String> findVectorIds(String documentId, long generation) {
        return findVectorIds(identityFor(documentId, generation));
    }

    public String findEmbeddingProfileId(GenerationIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        return jdbcTemplate.query(
                """
                SELECT embedding_profile_id
                FROM knowledge_document_vector_generation
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                  AND embedding_profile_id IS NOT NULL
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getString(1),
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
        ).stream().findFirst().orElse(null);
    }

    public String findEmbeddingProfileId(String documentId, long generation) {
        return findEmbeddingProfileId(
                identityFor(documentId, generation)
        );
    }

    public void deleteGeneration(GenerationIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity must not be null"
            );
        }
        jdbcTemplate.update(
                """
                DELETE FROM knowledge_document_vector_generation
                WHERE access_level = ?
                  AND document_id = ?
                  AND generation = ?
                """,
                identity.accessLevel(),
                identity.documentId(),
                identity.generation()
        );
    }

    public void deleteGeneration(String documentId, long generation) {
        deleteGeneration(identityFor(documentId, generation));
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

    public record VectorGenerationEntry(String vectorId, String chunkId) {
    }
}
