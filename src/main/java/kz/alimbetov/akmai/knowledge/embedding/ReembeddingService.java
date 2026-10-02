package kz.alimbetov.akmai.knowledge.embedding;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.alimbetov.akmai.config.ReembeddingProperties;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.ingestion.GenerationVectorAssembler;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReembeddingService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final EmbeddingProfileService profileService;
    private final EmbeddingProfileRepository profileRepository;
    private final GenerationEmbeddingService embeddingService;
    private final SearchProjectionRepository projections;
    private final DocumentIdentifierRepository identifiers;
    private final ReferenceGraphRepository references;
    private final VectorGenerationRepository manifests;
    private final PostgresGenerationVectorRepository vectors;
    private final GenerationVectorAssembler vectorAssembler;
    private final ReembeddingProperties properties;

    public ReembeddingService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            EmbeddingProfileService profileService,
            EmbeddingProfileRepository profileRepository,
            GenerationEmbeddingService embeddingService,
            SearchProjectionRepository projections,
            DocumentIdentifierRepository identifiers,
            ReferenceGraphRepository references,
            VectorGenerationRepository manifests,
            PostgresGenerationVectorRepository vectors,
            GenerationVectorAssembler vectorAssembler,
            ReembeddingProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.profileService = profileService;
        this.profileRepository = profileRepository;
        this.embeddingService = embeddingService;
        this.projections = projections;
        this.identifiers = identifiers;
        this.references = references;
        this.manifests = manifests;
        this.vectors = vectors;
        this.vectorAssembler = vectorAssembler;
        this.properties = properties;
    }

    public MigrationResult migrateToConfiguredProfile() {
        EmbeddingProfile source = profileService.activeProfile();
        EmbeddingProfile target = profileService.ensureConfiguredProfile();
        if (source.profileId().equals(target.profileId())) {
            return new MigrationResult(null, 0, false);
        }

        UUID migrationId = begin(source, target);
        try {
            awaitDrain(migrationId);
            snapshot(migrationId, source, target);

            List<SnapshotDocument> documents = snapshotDocuments(migrationId);
            for (SnapshotDocument document : documents) {
                stageCandidate(
                        migrationId,
                        document,
                        target
                );
            }

            readyToCutover(migrationId);
            cutover(migrationId, source, target);
            return new MigrationResult(
                    migrationId,
                    documents.size(),
                    true
            );
        } catch (RuntimeException exception) {
            abort(
                    migrationId,
                    exception.getClass().getSimpleName()
                            + ": "
                            + safe(exception.getMessage())
            );
            throw exception;
        }
    }

    public int abortInterruptedMigrations() {
        List<UUID> active = jdbcTemplate.query(
                """
                SELECT migration_id
                FROM knowledge_embedding_migration
                WHERE migration_status IN (
                    'PREPARING', 'STAGING', 'READY_TO_CUTOVER'
                )
                ORDER BY created_at
                """,
                (rs, rowNum) -> rs.getObject(1, UUID.class)
        );
        active.forEach(id -> abort(id, "PROCESS_RESTART_RECOVERY"));
        return active.size();
    }

    private UUID begin(
            EmbeddingProfile source,
            EmbeddingProfile target
    ) {
        UUID migrationId = UUID.randomUUID();
        return transactionTemplate.execute(status -> {
            RuntimeState runtime = lockRuntime();
            if (!"IDLE".equals(runtime.status())) {
                throw new IllegalStateException(
                        "Embedding migration is already active"
                );
            }
            if (!source.profileId().equals(runtime.activeProfileId())) {
                throw new IllegalStateException(
                        "Active embedding profile changed before migration"
                );
            }

            jdbcTemplate.update(
                    """
                    INSERT INTO knowledge_embedding_migration (
                        migration_id, source_profile_id, target_profile_id,
                        migration_status
                    ) VALUES (?, ?, ?, 'PREPARING')
                    """,
                    migrationId,
                    source.profileId(),
                    target.profileId()
            );
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_runtime
                    SET migration_profile_id = ?,
                        migration_status = 'PREPARING',
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE singleton_id = 1
                    """,
                    target.profileId()
            );
            return migrationId;
        });
    }

    private void awaitDrain(UUID migrationId) {
        Instant deadline = Instant.now().plus(properties.drainTimeout());
        while (Instant.now().isBefore(deadline)) {
            Integer active = jdbcTemplate.queryForObject(
                    """
                    SELECT
                        (SELECT count(*)
                         FROM knowledge_document_generation
                         WHERE generation_kind = 'INGESTION'
                           AND generation_status = 'STAGING')
                      + (SELECT count(*)
                         FROM knowledge_document_lifecycle
                         WHERE retention_status IN (
                             'DELETE_PENDING', 'DELETING'
                         )
                           AND lease_until > clock_timestamp())
                    """,
                    Integer.class
            );
            if (active != null && active == 0) {
                return;
            }
            sleep(properties.pollInterval());
        }
        throw new IllegalStateException(
                "Timed out draining ingestion/retention before migration "
                        + migrationId
        );
    }

    private void snapshot(
            UUID migrationId,
            EmbeddingProfile source,
            EmbeddingProfile target
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            RuntimeState runtime = lockRuntime();
            if (!"PREPARING".equals(runtime.status())
                    || !source.profileId().equals(runtime.activeProfileId())) {
                throw new IllegalStateException(
                        "Embedding migration lost PREPARING authority"
                );
            }

            Integer incompatible = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*)
                    FROM knowledge_document_lifecycle l
                    JOIN knowledge_document_generation g
                      ON g.document_id = l.document_id
                     AND g.generation = l.published_generation
                    WHERE l.retention_status = 'ACTIVE'
                      AND l.published_generation IS NOT NULL
                      AND g.embedding_profile_id IS DISTINCT FROM ?
                    """,
                    Integer.class,
                    source.profileId()
            );
            if (incompatible != null && incompatible > 0) {
                throw new IllegalStateException(
                        "Published corpus contains mixed embedding profiles"
                );
            }

            jdbcTemplate.update(
                    """
                    INSERT INTO knowledge_embedding_migration_document (
                        migration_id, document_id, source_generation,
                        document_status
                    )
                    SELECT ?, l.document_id, l.published_generation, 'SNAPSHOT'
                    FROM knowledge_document_lifecycle l
                    WHERE l.retention_status = 'ACTIVE'
                      AND l.published_generation IS NOT NULL
                    ORDER BY l.document_id
                    """,
                    migrationId
            );

            jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_migration
                    SET migration_status = 'STAGING',
                        updated_at = clock_timestamp()
                    WHERE migration_id = ?
                      AND migration_status = 'PREPARING'
                    """,
                    migrationId
            );
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_runtime
                    SET migration_status = 'STAGING',
                        migration_profile_id = ?,
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE singleton_id = 1
                    """,
                    target.profileId()
            );
        });
    }

    private List<SnapshotDocument> snapshotDocuments(UUID migrationId) {
        return jdbcTemplate.query(
                """
                SELECT document_id, source_generation
                FROM knowledge_embedding_migration_document
                WHERE migration_id = ?
                ORDER BY document_id
                """,
                (rs, rowNum) -> new SnapshotDocument(
                        rs.getString("document_id"),
                        rs.getLong("source_generation")
                ),
                migrationId
        );
    }

    private void stageCandidate(
            UUID migrationId,
            SnapshotDocument snapshot,
            EmbeddingProfile target
    ) {
        long candidate = allocateCandidate(
                migrationId,
                snapshot,
                target
        );

        try {
            List<SearchProjection> sourceProjections =
                    projections.findGeneration(
                            snapshot.documentId(),
                            snapshot.sourceGeneration()
                    );
            if (sourceProjections.isEmpty()) {
                throw new IllegalStateException(
                        "Published source generation has no projections"
                );
            }

            List<SearchProjection> cloned = sourceProjections.stream()
                    .map(value -> value.withGeneration(candidate))
                    .toList();
            List<DocumentIdentifier> clonedIdentifiers =
                    identifiers.findGeneration(
                                    snapshot.documentId(),
                                    snapshot.sourceGeneration()
                            ).stream()
                            .map(value -> value.withGeneration(candidate))
                            .toList();

            List<float[]> embeddings = embeddingService.embed(
                    cloned,
                    target
            );
            GenerationVectorAssembler.Assembly assembly =
                    vectorAssembler.assemble(
                            cloned,
                            embeddings,
                            target,
                            candidate
                    );

            transactionTemplate.executeWithoutResult(status -> {
                int owned = jdbcTemplate.update(
                        """
                        UPDATE knowledge_embedding_migration_document
                        SET updated_at = clock_timestamp()
                        WHERE migration_id = ?
                          AND document_id = ?
                          AND candidate_generation = ?
                          AND document_status = 'STAGING'
                        """,
                        migrationId,
                        snapshot.documentId(),
                        candidate
                );
                if (owned != 1) {
                    throw new IllegalStateException(
                            "Migration document lost staging authority"
                    );
                }

                projections.saveAll(cloned);
                identifiers.saveAll(clonedIdentifiers);
                references.cloneGeneration(
                        snapshot.documentId(),
                        snapshot.sourceGeneration(),
                        candidate
                );
                manifests.save(
                        snapshot.documentId(),
                        candidate,
                        target.profileId(),
                        (short) 2,
                        assembly.manifest()
                );
                vectors.insertAll(target, assembly.vectors());

                jdbcTemplate.update(
                        """
                        UPDATE knowledge_embedding_migration_document
                        SET document_status = 'VERIFIED',
                            last_error = NULL,
                            updated_at = clock_timestamp()
                        WHERE migration_id = ?
                          AND document_id = ?
                          AND candidate_generation = ?
                          AND document_status = 'STAGING'
                        """,
                        migrationId,
                        snapshot.documentId(),
                        candidate
                );
            });
        } catch (RuntimeException exception) {
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_generation
                    SET generation_status = 'FAILED',
                        failure_code = 'REEMBEDDING_STAGE_FAILED',
                        last_error = ?,
                        cleanup_required = true,
                        failed_at = clock_timestamp()
                    WHERE document_id = ?
                      AND generation = ?
                      AND generation_status = 'STAGING'
                    """,
                    safe(exception.getMessage()),
                    snapshot.documentId(),
                    candidate
            );
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_migration_document
                    SET document_status = 'FAILED',
                        last_error = ?,
                        updated_at = clock_timestamp()
                    WHERE migration_id = ?
                      AND document_id = ?
                    """,
                    safe(exception.getMessage()),
                    migrationId,
                    snapshot.documentId()
            );
            throw exception;
        }
    }

    private long allocateCandidate(
            UUID migrationId,
            SnapshotDocument snapshot,
            EmbeddingProfile target
    ) {
        return transactionTemplate.execute(status -> {
            RuntimeState runtime = lockRuntime();
            if (!"STAGING".equals(runtime.status())
                    || !target.profileId().equals(
                            runtime.migrationProfileId()
                    )) {
                throw new IllegalStateException(
                        "Embedding migration is not in STAGING state"
                );
            }

            LifecycleSnapshot lifecycle = jdbcTemplate.query(
                    """
                    SELECT published_generation, next_generation
                    FROM knowledge_document_lifecycle
                    WHERE document_id = ?
                    FOR UPDATE
                    """,
                    (rs, rowNum) -> new LifecycleSnapshot(
                            rs.getLong("published_generation"),
                            rs.getLong("next_generation")
                    ),
                    snapshot.documentId()
            ).stream().findFirst().orElseThrow();

            if (lifecycle.publishedGeneration()
                    != snapshot.sourceGeneration()) {
                throw new IllegalStateException(
                        "Published generation changed during embedding migration"
                );
            }

            String fingerprint = jdbcTemplate.queryForObject(
                    """
                    SELECT content_fingerprint
                    FROM knowledge_document_generation
                    WHERE document_id = ?
                      AND generation = ?
                    """,
                    String.class,
                    snapshot.documentId(),
                    snapshot.sourceGeneration()
            );

            long candidate = lifecycle.nextGeneration();
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_lifecycle
                    SET next_generation = ?,
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE document_id = ?
                    """,
                    candidate + 1,
                    snapshot.documentId()
            );
            jdbcTemplate.update(
                    """
                    INSERT INTO knowledge_document_generation (
                        document_id, generation, generation_status,
                        generation_kind, migration_id, embedding_profile_id,
                        content_fingerprint, physical_id_version,
                        cleanup_required, started_at
                    ) VALUES (
                        ?, ?, 'STAGING', 'REEMBEDDING', ?, ?, ?, 2,
                        false, clock_timestamp()
                    )
                    """,
                    snapshot.documentId(),
                    candidate,
                    migrationId,
                    target.profileId(),
                    fingerprint
            );
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_migration_document
                    SET candidate_generation = ?,
                        document_status = 'STAGING',
                        updated_at = clock_timestamp()
                    WHERE migration_id = ?
                      AND document_id = ?
                      AND document_status = 'SNAPSHOT'
                    """,
                    candidate,
                    migrationId,
                    snapshot.documentId()
            );
            return candidate;
        });
    }

    private void readyToCutover(UUID migrationId) {
        transactionTemplate.executeWithoutResult(status -> {
            Integer pending = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*)
                    FROM knowledge_embedding_migration_document
                    WHERE migration_id = ?
                      AND document_status <> 'VERIFIED'
                    """,
                    Integer.class,
                    migrationId
            );
            if (pending != null && pending > 0) {
                throw new IllegalStateException(
                        "Not every migration document is verified"
                );
            }

            jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_migration
                    SET migration_status = 'READY_TO_CUTOVER',
                        updated_at = clock_timestamp()
                    WHERE migration_id = ?
                      AND migration_status = 'STAGING'
                    """,
                    migrationId
            );
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_runtime
                    SET migration_status = 'READY_TO_CUTOVER',
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE singleton_id = 1
                      AND migration_status = 'STAGING'
                    """
            );
        });
    }

    private void cutover(
            UUID migrationId,
            EmbeddingProfile source,
            EmbeddingProfile target
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            RuntimeState runtime = lockRuntime();
            if (!"READY_TO_CUTOVER".equals(runtime.status())
                    || !source.profileId().equals(runtime.activeProfileId())
                    || !target.profileId().equals(
                            runtime.migrationProfileId()
                    )) {
                throw new IllegalStateException(
                        "Embedding migration is not ready for cutover"
                );
            }

            Integer invalid = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*)
                    FROM knowledge_embedding_migration_document d
                    JOIN knowledge_document_lifecycle l
                      ON l.document_id = d.document_id
                    JOIN knowledge_document_generation candidate
                      ON candidate.document_id = d.document_id
                     AND candidate.generation = d.candidate_generation
                    WHERE d.migration_id = ?
                      AND (
                          d.document_status <> 'VERIFIED'
                          OR l.published_generation <> d.source_generation
                          OR candidate.generation_status <> 'STAGING'
                          OR candidate.embedding_profile_id <> ?
                      )
                    """,
                    Integer.class,
                    migrationId,
                    target.profileId()
            );
            if (invalid != null && invalid > 0) {
                throw new IllegalStateException(
                        "Embedding migration snapshot is no longer valid"
                );
            }

            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_generation source
                    SET generation_status = 'RETIRED',
                        retired_at = clock_timestamp()
                    FROM knowledge_embedding_migration_document d
                    WHERE d.migration_id = ?
                      AND source.document_id = d.document_id
                      AND source.generation = d.source_generation
                      AND source.generation_status = 'PUBLISHED'
                    """,
                    migrationId
            );
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_generation candidate
                    SET generation_status = 'PUBLISHED',
                        published_at = clock_timestamp()
                    FROM knowledge_embedding_migration_document d
                    WHERE d.migration_id = ?
                      AND candidate.document_id = d.document_id
                      AND candidate.generation = d.candidate_generation
                      AND candidate.generation_status = 'STAGING'
                    """,
                    migrationId
            );
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_lifecycle l
                    SET published_generation = d.candidate_generation,
                        generation = d.candidate_generation,
                        lifecycle_status = 'READY',
                        row_version = l.row_version + 1,
                        updated_at = clock_timestamp()
                    FROM knowledge_embedding_migration_document d
                    WHERE d.migration_id = ?
                      AND l.document_id = d.document_id
                      AND l.published_generation = d.source_generation
                    """,
                    migrationId
            );

            jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_runtime
                    SET active_profile_id = ?,
                        migration_profile_id = NULL,
                        migration_status = 'IDLE',
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE singleton_id = 1
                    """,
                    target.profileId()
            );
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_migration
                    SET migration_status = 'COMPLETED',
                        completed_at = clock_timestamp(),
                        updated_at = clock_timestamp(),
                        last_error = NULL
                    WHERE migration_id = ?
                    """,
                    migrationId
            );
        });
    }

    private void abort(UUID migrationId, String error) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_generation g
                    SET generation_status = 'FAILED',
                        failure_code = 'REEMBEDDING_ABORTED',
                        last_error = ?,
                        cleanup_required = true,
                        failed_at = clock_timestamp()
                    FROM knowledge_embedding_migration_document d
                    WHERE d.migration_id = ?
                      AND d.document_id = g.document_id
                      AND d.candidate_generation = g.generation
                      AND g.generation_status = 'STAGING'
                    """,
                    safe(error),
                    migrationId
            );
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_migration_document
                    SET document_status = 'FAILED',
                        last_error = ?,
                        updated_at = clock_timestamp()
                    WHERE migration_id = ?
                      AND document_status <> 'FAILED'
                    """,
                    safe(error),
                    migrationId
            );
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_migration
                    SET migration_status = 'FAILED',
                        last_error = ?,
                        updated_at = clock_timestamp()
                    WHERE migration_id = ?
                      AND migration_status <> 'COMPLETED'
                    """,
                    safe(error),
                    migrationId
            );
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_runtime runtime
                    SET migration_profile_id = NULL,
                        migration_status = 'IDLE',
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE singleton_id = 1
                      AND EXISTS (
                          SELECT 1
                          FROM knowledge_embedding_migration migration
                          WHERE migration.migration_id = ?
                            AND migration.target_profile_id =
                                runtime.migration_profile_id
                      )
                    """,
                    migrationId
            );
        });
    }

    private RuntimeState lockRuntime() {
        return jdbcTemplate.queryForObject(
                """
                SELECT active_profile_id, migration_profile_id,
                       migration_status
                FROM knowledge_embedding_runtime
                WHERE singleton_id = 1
                FOR UPDATE
                """,
                (rs, rowNum) -> new RuntimeState(
                        rs.getString("active_profile_id"),
                        rs.getString("migration_profile_id"),
                        rs.getString("migration_status")
                )
        );
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Embedding migration drain interrupted",
                    exception
            );
        }
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "embedding migration failed";
        }
        String clean = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.length() <= 1000 ? clean : clean.substring(0, 1000);
    }

    private record RuntimeState(
            String activeProfileId,
            String migrationProfileId,
            String status
    ) {
    }

    private record LifecycleSnapshot(
            long publishedGeneration,
            long nextGeneration
    ) {
    }

    private record SnapshotDocument(
            String documentId,
            long sourceGeneration
    ) {
    }

    public record MigrationResult(
            UUID migrationId,
            int documents,
            boolean migrated
    ) {
    }
}
