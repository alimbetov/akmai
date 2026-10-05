package kz.alimbetov.akmai.health;

import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("pgvector")
public class PgVectorHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProfileService profileService;

    public PgVectorHealthIndicator(
            JdbcTemplate jdbcTemplate,
            EmbeddingProfileService profileService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.profileService = profileService;
    }

    @Override
    public Health health() {
        try {
            Boolean extension = jdbcTemplate.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_extension
                        WHERE extname = 'vector'
                    )
                    """,
                    Boolean.class
            );
            if (!Boolean.TRUE.equals(extension)) {
                return Health.down()
                        .withDetail("reason", "pgvector extension is missing")
                        .build();
            }

            EmbeddingProfile profile = profileService.activeProfile();
            String relation = profile.vectorSchema()
                    + "."
                    + profile.vectorTable();

            String actualType = jdbcTemplate.query(
                    """
                    SELECT format_type(a.atttypid, a.atttypmod)
                    FROM pg_attribute a
                    WHERE a.attrelid = to_regclass(?)
                      AND a.attname = 'embedding'
                      AND NOT a.attisdropped
                    """,
                    (rs, rowNum) -> rs.getString(1),
                    relation
            ).stream().findFirst().orElse(null);

            String expectedType = "vector(" + profile.dimensions() + ")";
            if (!expectedType.equals(actualType)) {
                return Health.down()
                        .withDetail("reason", "vector storage dimension mismatch")
                        .withDetail("relation", relation)
                        .withDetail("expectedType", expectedType)
                        .withDetail("actualType", actualType == null ? "missing" : actualType)
                        .build();
            }

            return Health.up()
                    .withDetail("relation", relation)
                    .withDetail("type", actualType)
                    .build();
        } catch (RuntimeException exception) {
            return Health.down()
                    .withDetail("reason", exception.getClass().getSimpleName())
                    .build();
        }
    }
}
