package kz.alimbetov.akmai.knowledge.embedding;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmbeddingProfileRepository {

    private final JdbcTemplate jdbcTemplate;

    public EmbeddingProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(EmbeddingProfile profile) {
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_embedding_profile (
                    profile_id, provider, model, dimensions, distance_type,
                    tokenizer_profile, config_fingerprint, vector_schema,
                    vector_table, index_type, storage_schema_version, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (profile_id) DO NOTHING
                """,
                profile.profileId(),
                profile.provider(),
                profile.model(),
                profile.dimensions(),
                profile.distanceType(),
                profile.tokenizerProfile(),
                profile.configFingerprint(),
                profile.vectorSchema(),
                profile.vectorTable(),
                profile.indexType(),
                profile.storageSchemaVersion(),
                java.sql.Timestamp.from(profile.createdAt())
        );
    }

    public Optional<EmbeddingProfile> findById(String profileId) {
        return jdbcTemplate.query(
                """
                SELECT *
                FROM knowledge_embedding_profile
                WHERE profile_id = ?
                """,
                this::map,
                profileId
        ).stream().findFirst();
    }

    public EmbeddingRuntime runtime() {
        return jdbcTemplate.queryForObject(
                "SELECT * FROM knowledge_embedding_runtime WHERE singleton_id = 1",
                (rs, rowNum) -> new EmbeddingRuntime(
                        rs.getString("active_profile_id"),
                        rs.getString("migration_profile_id"),
                        EmbeddingRuntime.MigrationStatus.valueOf(
                                rs.getString("migration_status")
                        ),
                        rs.getLong("row_version"),
                        rs.getTimestamp("updated_at").toInstant()
                )
        );
    }

    public boolean activateIfEmpty(String profileId) {
        return jdbcTemplate.update(
                """
                UPDATE knowledge_embedding_runtime
                SET active_profile_id = ?,
                    row_version = row_version + 1,
                    updated_at = clock_timestamp()
                WHERE singleton_id = 1
                  AND active_profile_id IS NULL
                  AND NOT EXISTS (
                      SELECT 1
                      FROM knowledge_document_lifecycle
                      WHERE published_generation IS NOT NULL
                  )
                """,
                profileId
        ) == 1;
    }

    private EmbeddingProfile map(ResultSet rs, int rowNum) throws SQLException {
        return new EmbeddingProfile(
                rs.getString("profile_id"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getInt("dimensions"),
                rs.getString("distance_type"),
                rs.getString("tokenizer_profile"),
                rs.getString("config_fingerprint"),
                rs.getString("vector_schema"),
                rs.getString("vector_table"),
                rs.getString("index_type"),
                rs.getShort("storage_schema_version"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
