package kz.alimbetov.akmai.knowledge.graph;

import com.pgvector.PGvector;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SemanticNeighborSearchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProfileService profileService;
    private final EmbeddingProfileStorageManager storageManager;

    public SemanticNeighborSearchRepository(
            JdbcTemplate jdbcTemplate,
            EmbeddingProfileService profileService,
            EmbeddingProfileStorageManager storageManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.profileService = profileService;
        this.storageManager = storageManager;
    }

    public List<SemanticNeighbor> search(
            float[] embedding,
            String language,
            long accessLevel,
            int topK,
            double minimumSimilarity,
            boolean sameLanguageOnly
    ) {
        if (accessLevel <= 0) {
            throw new IllegalArgumentException("accessLevel must be positive");
        }
        if (topK <= 0 || topK > 256) {
            throw new IllegalArgumentException("topK must be in [1, 256]");
        }
        if (!Double.isFinite(minimumSimilarity)
                || minimumSimilarity < 0
                || minimumSimilarity > 1) {
            throw new IllegalArgumentException(
                    "minimumSimilarity must be in [0, 1]"
            );
        }

        profileService.assertConfiguredProfileIsActive();
        EmbeddingProfile profile = profileService.activeProfile();
        validateEmbedding(embedding, profile.dimensions());

        String routedLanguage = normalizeLanguage(language);
        PGvector vector = new PGvector(embedding);
        double maximumDistance = 1.0 - minimumSimilarity;
        String languagePredicate = sameLanguageOnly
                ? " AND v.language = ? "
                : "";
        String table = storageManager.qualified(profile);

        String sql = """
                SELECT v.access_level,
                       v.document_id,
                       v.generation,
                       v.chunk_id,
                       v.language,
                       1.0 - (v.embedding <=> ?) AS similarity
                FROM %s v
                JOIN knowledge_document_lifecycle l
                  ON l.document_id = v.document_id
                 AND l.access_level = v.access_level
                 AND l.published_generation = v.generation
                WHERE v.access_level = ?
                  AND l.lifecycle_status = 'READY'
                  AND l.retention_status = 'ACTIVE'
                  %s
                  AND (v.embedding <=> ?) <= ?
                ORDER BY v.embedding <=> ?,
                         v.document_id,
                         v.generation,
                         v.chunk_id
                LIMIT ?
                """.formatted(table, languagePredicate);

        return jdbcTemplate.query(
                sql,
                ps -> bind(
                        ps,
                        vector,
                        accessLevel,
                        routedLanguage,
                        sameLanguageOnly,
                        maximumDistance,
                        topK
                ),
                (rs, rowNum) -> new SemanticNeighbor(
                        new ChunkGraphNode(
                                rs.getLong("access_level"),
                                rs.getString("document_id"),
                                rs.getLong("generation"),
                                rs.getString("chunk_id")
                        ),
                        rs.getString("language"),
                        rs.getDouble("similarity")
                )
        );
    }

    private void bind(
            PreparedStatement ps,
            PGvector vector,
            long accessLevel,
            String language,
            boolean sameLanguageOnly,
            double maximumDistance,
            int topK
    ) throws SQLException {
        int index = 1;
        ps.setObject(index++, vector);
        ps.setLong(index++, accessLevel);
        if (sameLanguageOnly) {
            ps.setString(index++, language);
        }
        ps.setObject(index++, vector);
        ps.setDouble(index++, maximumDistance);
        ps.setObject(index++, vector);
        ps.setInt(index, topK);
    }

    private String normalizeLanguage(String language) {
        if (language == null || !language.matches("[a-zA-Z]{2,8}")) {
            throw new IllegalArgumentException(
                    "language must be a normalized language code"
            );
        }
        return language.toLowerCase(Locale.ROOT);
    }

    private void validateEmbedding(float[] embedding, int dimensions) {
        if (embedding == null || embedding.length != dimensions) {
            throw new IllegalArgumentException(
                    "embedding has incompatible dimensions"
            );
        }
        for (float component : embedding) {
            if (!Float.isFinite(component)) {
                throw new IllegalArgumentException(
                        "embedding contains non-finite component"
                );
            }
        }
    }

    public record SemanticNeighbor(
            ChunkGraphNode node,
            String language,
            double similarity
    ) {
        public SemanticNeighbor {
            if (node == null) {
                throw new IllegalArgumentException("node must not be null");
            }
            if (language == null || language.isBlank()) {
                throw new IllegalArgumentException("language must not be blank");
            }
            if (!Double.isFinite(similarity)
                    || similarity < 0
                    || similarity > 1) {
                throw new IllegalArgumentException(
                        "similarity must be in [0, 1]"
                );
            }
        }
    }
}
