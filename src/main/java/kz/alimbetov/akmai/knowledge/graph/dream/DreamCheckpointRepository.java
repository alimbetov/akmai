package kz.alimbetov.akmai.knowledge.graph.dream;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Monotonic checkpoint updates guarded by a live Dream lease/fencing token. */
@Repository
public class DreamCheckpointRepository {

    private final JdbcTemplate jdbcTemplate;

    public DreamCheckpointRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Checkpoint> find(int graphVersion, String fingerprint) {
        return jdbcTemplate.query(
                """
                SELECT graph_version,
                       semantic_policy_fingerprint,
                       fast_watermark_updated_at,
                       fast_watermark_access_level,
                       fast_watermark_document_id,
                       fast_watermark_generation,
                       fast_watermark_chunk_id,
                       rescan_cursor,
                       last_successful_run_id,
                       last_completed_at,
                       fencing_token
                FROM adaptive_graph_dream_checkpoint
                WHERE graph_version = ?
                  AND semantic_policy_fingerprint = ?
                """,
                (rs, rowNum) -> new Checkpoint(
                        rs.getInt("graph_version"),
                        rs.getString("semantic_policy_fingerprint"),
                        rs.getTimestamp("fast_watermark_updated_at") == null
                                ? null
                                : rs.getTimestamp("fast_watermark_updated_at").toInstant(),
                        rs.getObject("fast_watermark_access_level", Long.class),
                        rs.getString("fast_watermark_document_id"),
                        rs.getObject("fast_watermark_generation", Long.class),
                        rs.getString("fast_watermark_chunk_id"),
                        rs.getString("rescan_cursor"),
                        rs.getObject("last_successful_run_id", UUID.class),
                        rs.getTimestamp("last_completed_at") == null
                                ? null
                                : rs.getTimestamp("last_completed_at").toInstant(),
                        rs.getLong("fencing_token")
                ),
                graphVersion,
                fingerprint
        ).stream().findFirst();
    }

    public void initialize(DreamLeaseManager.Authority authority) {
        requireAuthority(authority);
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO adaptive_graph_dream_checkpoint (
                    graph_version,
                    semantic_policy_fingerprint,
                    fencing_token
                )
                SELECT ?, ?, ?
                WHERE EXISTS (
                    SELECT 1
                    FROM adaptive_graph_dream_lease lease
                    WHERE lease.graph_version = ?
                      AND lease.semantic_policy_fingerprint = ?
                      AND lease.owner_id = ?
                      AND lease.fencing_token = ?
                      AND lease.lease_until > clock_timestamp()
                )
                ON CONFLICT (graph_version, semantic_policy_fingerprint)
                DO UPDATE SET
                    fencing_token = EXCLUDED.fencing_token,
                    updated_at = clock_timestamp()
                WHERE adaptive_graph_dream_checkpoint.fencing_token
                        <= EXCLUDED.fencing_token
                """,
                authority.graphVersion(),
                authority.policyFingerprint(),
                authority.fencingToken(),
                authority.graphVersion(),
                authority.policyFingerprint(),
                authority.ownerId(),
                authority.fencingToken()
        );
        if (inserted != 1) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Cannot initialize Dream checkpoint without live authority"
            );
        }
    }

    /**
     * Compare-and-set fast-lane watermark. Both expected and next are explicit
     * so a stale/replayed worker cannot skip an unprocessed source range.
     */
    public void advanceFastWatermark(
            DreamLeaseManager.Authority authority,
            Watermark expected,
            Watermark next,
            UUID completedRunId
    ) {
        requireAuthority(authority);
        if (next == null) {
            throw new IllegalArgumentException("next watermark must not be null");
        }
        if (expected != null && next.compareTo(expected) < 0) {
            throw new IllegalArgumentException("Dream watermark must be monotonic");
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE adaptive_graph_dream_checkpoint checkpoint
                SET fast_watermark_updated_at = ?,
                    fast_watermark_access_level = ?,
                    fast_watermark_document_id = ?,
                    fast_watermark_generation = ?,
                    fast_watermark_chunk_id = ?,
                    last_successful_run_id = ?,
                    last_completed_at = clock_timestamp(),
                    fencing_token = ?,
                    updated_at = clock_timestamp()
                WHERE checkpoint.graph_version = ?
                  AND checkpoint.semantic_policy_fingerprint = ?
                  AND (
                      (?::timestamptz IS NULL
                       AND checkpoint.fast_watermark_updated_at IS NULL)
                      OR (
                          checkpoint.fast_watermark_updated_at = ?
                          AND checkpoint.fast_watermark_access_level = ?
                          AND checkpoint.fast_watermark_document_id = ?
                          AND checkpoint.fast_watermark_generation = ?
                          AND checkpoint.fast_watermark_chunk_id = ?
                      )
                  )
                  AND EXISTS (
                      SELECT 1
                      FROM adaptive_graph_dream_lease lease
                      WHERE lease.graph_version = checkpoint.graph_version
                        AND lease.semantic_policy_fingerprint = checkpoint.semantic_policy_fingerprint
                        AND lease.owner_id = ?
                        AND lease.fencing_token = ?
                        AND lease.lease_until > clock_timestamp()
                  )
                """,
                Timestamp.from(next.updatedAt()),
                next.accessLevel(),
                next.documentId(),
                next.generation(),
                next.chunkId(),
                completedRunId,
                authority.fencingToken(),
                authority.graphVersion(),
                authority.policyFingerprint(),
                expected == null ? null : Timestamp.from(expected.updatedAt()),
                expected == null ? null : Timestamp.from(expected.updatedAt()),
                expected == null ? null : expected.accessLevel(),
                expected == null ? null : expected.documentId(),
                expected == null ? null : expected.generation(),
                expected == null ? null : expected.chunkId(),
                authority.ownerId(),
                authority.fencingToken()
        );
        if (updated != 1) {
            throw new DreamLeaseManager.LostDreamAuthorityException(
                    "Dream checkpoint CAS failed or authority was lost"
            );
        }
    }

    private void requireAuthority(DreamLeaseManager.Authority authority) {
        if (authority == null) {
            throw new IllegalArgumentException("Dream authority must not be null");
        }
    }

    public record Watermark(
            Instant updatedAt,
            long accessLevel,
            String documentId,
            long generation,
            String chunkId
    ) implements Comparable<Watermark> {
        public Watermark {
            if (updatedAt == null || accessLevel <= 0 || generation <= 0
                    || documentId == null || documentId.isBlank()
                    || chunkId == null || chunkId.isBlank()) {
                throw new IllegalArgumentException("invalid Dream watermark");
            }
        }

        @Override
        public int compareTo(Watermark other) {
            int time = updatedAt.compareTo(other.updatedAt);
            if (time != 0) {
                return time;
            }
            int acl = Long.compare(accessLevel, other.accessLevel);
            if (acl != 0) {
                return acl;
            }
            int document = documentId.compareTo(other.documentId);
            if (document != 0) {
                return document;
            }
            int generationOrder = Long.compare(generation, other.generation);
            return generationOrder != 0
                    ? generationOrder
                    : chunkId.compareTo(other.chunkId);
        }
    }

    public record Checkpoint(
            int graphVersion,
            String policyFingerprint,
            Instant fastWatermarkUpdatedAt,
            Long fastWatermarkAccessLevel,
            String fastWatermarkDocumentId,
            Long fastWatermarkGeneration,
            String fastWatermarkChunkId,
            String rescanCursor,
            UUID lastSuccessfulRunId,
            Instant lastCompletedAt,
            long fencingToken
    ) {
        public Optional<Watermark> fastWatermark() {
            if (fastWatermarkUpdatedAt == null) {
                return Optional.empty();
            }
            return Optional.of(new Watermark(
                    fastWatermarkUpdatedAt,
                    fastWatermarkAccessLevel,
                    fastWatermarkDocumentId,
                    fastWatermarkGeneration,
                    fastWatermarkChunkId
            ));
        }
    }
}
