package kz.alimbetov.akmai.knowledge.embedding;

import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingProfileStorageManager {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final int HNSW_MAX_DIMENSIONS = 2000;

    private final JdbcTemplate jdbcTemplate;

    public EmbeddingProfileStorageManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensureStorage(EmbeddingProfile profile) {
        validate(profile);
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS akmai_vector");

        if (hasGreenfieldProvisioner()) {
            jdbcTemplate.query(
                    """
                    SELECT akmai_admin.ensure_vector_profile_storage(
                        ?,
                        ?
                    )
                    """,
                    rs -> {
                    },
                    profile.vectorTable(),
                    profile.dimensions()
            );
            return;
        }

        String table = qualified(profile);
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS %s (
                    id UUID PRIMARY KEY,
                    content TEXT NOT NULL,
                    metadata JSONB NOT NULL,
                    embedding VECTOR(%d) NOT NULL
                )
                """.formatted(table, profile.dimensions())
        );

        if ("HNSW".equals(profile.indexType())) {
            String index = "idx_" + profile.vectorTable() + "_embedding";
            jdbcTemplate.execute(
                    """
                    CREATE INDEX IF NOT EXISTS %s
                    ON %s USING HNSW (embedding vector_cosine_ops)
                    """.formatted(index, table)
            );
        }
    }

    private boolean hasGreenfieldProvisioner() {
        Boolean result = jdbcTemplate.queryForObject(
                """
                SELECT to_regprocedure(
                    'akmai_admin.ensure_vector_profile_storage(text,integer)'
                ) IS NOT NULL
                """,
                Boolean.class
        );
        return Boolean.TRUE.equals(result);
    }

    public String qualified(EmbeddingProfile profile) {
        validateIdentifier(profile.vectorSchema());
        validateIdentifier(profile.vectorTable());
        return profile.vectorSchema() + "." + profile.vectorTable();
    }

    private void validate(EmbeddingProfile profile) {
        if (!"akmai_vector".equals(profile.vectorSchema())) {
            throw new IllegalArgumentException("profile vector schema must be akmai_vector");
        }
        validateIdentifier(profile.vectorTable());
        if (!"COSINE_DISTANCE".equals(profile.distanceType())) {
            throw new IllegalArgumentException("only cosine distance is supported");
        }
        if ("HNSW".equals(profile.indexType())
                && profile.dimensions() > HNSW_MAX_DIMENSIONS) {
            throw new IllegalArgumentException(
                    "HNSW supports at most 2000 dimensions"
            );
        }
        if (!"HNSW".equals(profile.indexType())
                && !"NONE".equals(profile.indexType())) {
            throw new IllegalArgumentException("unsupported vector index type");
        }
    }

    private void validateIdentifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid SQL identifier: " + value);
        }
    }
}
