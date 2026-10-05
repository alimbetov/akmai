package kz.alimbetov.akmai.knowledge.embedding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.alimbetov.akmai.config.ReembeddingProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReembeddingLeaseManager {

    private static final String ACTIVE_STATUSES =
            "('PREPARING', 'STAGING', 'READY_TO_CUTOVER')";

    private final JdbcTemplate jdbcTemplate;
    private final ReembeddingProperties properties;
    private final String ownerId;

    public ReembeddingLeaseManager(
            JdbcTemplate jdbcTemplate,
            ReembeddingProperties properties
    ) {
        this(jdbcTemplate, properties, UUID.randomUUID().toString());
    }

    ReembeddingLeaseManager(
            JdbcTemplate jdbcTemplate,
            ReembeddingProperties properties,
            String ownerId
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.ownerId = ownerId;
    }

    public Authority claimNew(UUID migrationId) {
        List<Long> tokens = jdbcTemplate.query(
                """
                UPDATE knowledge_embedding_migration
                SET owner_id = ?,
                    fencing_token = fencing_token + 1,
                    lease_until = clock_timestamp()
                        + (? * interval '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE migration_id = ?
                  AND owner_id IS NULL
                  AND fencing_token = 0
                  AND migration_status IN
                """ + ACTIVE_STATUSES + "\nRETURNING fencing_token",
                (rs, rowNum) -> rs.getLong(1),
                ownerId,
                properties.leaseDuration().toMillis(),
                migrationId
        );
        if (tokens.size() != 1) {
            throw new LostAuthorityException(
                    "Unable to establish re-embedding migration ownership"
            );
        }
        return new Authority(migrationId, ownerId, tokens.getFirst());
    }

    public Optional<Authority> claimExpiredActive() {
        List<Authority> claimed = jdbcTemplate.query(
                """
                WITH candidate AS (
                    SELECT migration_id
                    FROM knowledge_embedding_migration
                    WHERE migration_status IN
                """ + ACTIVE_STATUSES + """
                      AND (
                          lease_until IS NULL
                          OR lease_until <= clock_timestamp()
                      )
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE knowledge_embedding_migration migration
                SET owner_id = ?,
                    fencing_token = migration.fencing_token + 1,
                    lease_until = clock_timestamp()
                        + (? * interval '1 millisecond'),
                    updated_at = clock_timestamp()
                FROM candidate
                WHERE migration.migration_id = candidate.migration_id
                RETURNING migration.migration_id, migration.fencing_token
                """,
                (rs, rowNum) -> new Authority(
                        rs.getObject("migration_id", UUID.class),
                        ownerId,
                        rs.getLong("fencing_token")
                ),
                ownerId,
                properties.leaseDuration().toMillis()
        );
        return claimed.stream().findFirst();
    }

    public void renew(Authority authority) {
        int renewed = jdbcTemplate.update(
                """
                UPDATE knowledge_embedding_migration
                SET lease_until = clock_timestamp()
                        + (? * interval '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE migration_id = ?
                  AND owner_id = ?
                  AND fencing_token = ?
                  AND migration_status IN
                """ + ACTIVE_STATUSES + """
                  AND lease_until > clock_timestamp()
                """,
                properties.leaseDuration().toMillis(),
                authority.migrationId(),
                authority.ownerId(),
                authority.fencingToken()
        );
        if (renewed != 1) {
            throw new LostAuthorityException(
                    "Re-embedding migration lease is no longer owned"
            );
        }
    }

    public boolean isOwned(Authority authority) {
        Integer owned = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_embedding_migration
                WHERE migration_id = ?
                  AND owner_id = ?
                  AND fencing_token = ?
                  AND migration_status IN
                """ + ACTIVE_STATUSES + """
                  AND lease_until > clock_timestamp()
                """,
                Integer.class,
                authority.migrationId(),
                authority.ownerId(),
                authority.fencingToken()
        );
        return owned != null && owned == 1;
    }

    public record Authority(
            UUID migrationId,
            String ownerId,
            long fencingToken
    ) {
    }

    public static final class LostAuthorityException
            extends IllegalStateException {

        public LostAuthorityException(String message) {
            super(message);
        }
    }
}
