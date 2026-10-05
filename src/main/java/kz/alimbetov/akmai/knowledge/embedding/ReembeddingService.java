package kz.alimbetov.akmai.knowledge.embedding;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.alimbetov.akmai.config.ReembeddingProperties;
import kz.alimbetov.akmai.knowledge.embedding.ReembeddingLeaseManager.Authority;
import kz.alimbetov.akmai.knowledge.embedding.ReembeddingLeaseManager.LostAuthorityException;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.ingestion.GenerationVectorAssembler;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final ReembeddingLeaseManager leases;
    private final ReembeddingProperties properties;

    public ReembeddingService(
            JdbcTemplate jdbcTemplate,
            @Qualifier("reembeddingTransactionTemplate")
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
            ReembeddingLeaseManager leases,
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
        this.leases = leases;
        this.properties = properties;
    }

    public MigrationResult migrateToConfiguredProfile() {
        EmbeddingProfile source = profileService.activeProfile();
        EmbeddingProfile target = profileService.ensureConfiguredProfile();
        if (source.profileId().equals(target.profileId())) {
            return new MigrationResult(null, 0, false);
        }

        Authority authority = begin(source, target);
        if (authority == null) {
            return new MigrationResult(null, 0, false);
        }

        try {
            awaitDrain(authority);
            snapshot(authority, source, target);

            List<SnapshotDocument> documents = snapshotDocuments(authority);
            for (SnapshotDocument document : documents) {
                stageCandidate(authority, document, target);
            }

            readyToCutover(authority);
            cutover(authority, source, target);
            return new MigrationResult(
                    authority.migrationId(),
                    documents.size(),
                    true
            );
        } catch (RuntimeException exception) {
            boolean aborted = abortIfOwned(
                    authority,
                    exception.getClass().getSimpleName()
                            + ": "
                            + safe(exception.getMessage())
            );
            if (!aborted && exception instanceof LostAuthorityException) {
                recoverExpiredMigration();
            }
            throw exception;
        }
    }

    public int recoverExpiredMigration() {
        Optional<Authority> claimed = leases.claimExpiredActive();
        if (claimed.isEmpty()) {
            return 0;
        }
        return abortIfOwned(claimed.get(), "STALE_LEASE_RECOVERY") ? 1 : 0;
    }

    private Authority begin(
            EmbeddingProfile source,
            EmbeddingProfile target
    ) {
        UUID migrationId = UUID.randomUUID();
        return transactionTemplate.execute(status -> {
            RuntimeState runtime = lockRuntime();
            if (!"IDLE".equals(runtime.status())) {
                return null;
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
            Authority authority = leases.claimNew(migrationId);
            int updated = jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_runtime
                    SET migration_profile_id = ?,
                        migration_status = 'PREPARING',
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE singleton_id = 1
                      AND migration_status = 'IDLE'
                      AND active_profile_id = ?
                    """,
                    target.profileId(),
                    source.profileId()
            );
            if (updated != 1) {
                throw new IllegalStateException(
                        "Embedding runtime changed before migration ownership was established"
                );
            }
            return authority;
        });
    }

    private void awaitDrain(Authority authority) {
        Instant deadline = Instant.now().plus(properties.drainTimeout());
        while (Instant.now().isBefore(deadline)) {
            leases.renew(authority);
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
                        + authority.migrationId()
        );
    }

    private void snapshot(
            Authority authority,
            EmbeddingProfile source,
            EmbeddingProfile target
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            leases.renew(authority);
            RuntimeState runtime = lockRuntime();
            if (!"PREPARING".equals(runtime.status())
                    || !source.profileId().equals(runtime.activeProfileId())
                    || !target.profileId().equals(runtime.migrationProfileId())) {
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
                    authority.migrationId()
            );

            int migrationUpdated = jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_migration
                    SET migration_status = 'STAGING',
                        updated_at = clock_timestamp()
                    WHERE migration_id = ?
                      AND owner_id = ?
                      AND fencing_token = ?
                      AND migration_status = 'PREPARING'
                    """,
                    authority.migrationId(),
                    authority.ownerId(),
                    authority.fencingToken()
            );
            if (migrationUpdated != 1) {
                throw new LostAuthorityException(
                        "Re-embedding migration lost ownership before STAGING"
                );
            }

            int runtimeUpdated = jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_runtime
                    SET migration_status = 'STAGING',
                        migration_profile_id = ?,
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE singleton_id = 1
                      AND migration_status = 'PREPARING'
                      AND migration_profile_id = ?
                    """,
                    target.profileId(),
                    target.profileId()
            );
            if (runtimeUpdated != 1) {
                throw new IllegalStateException(
                        "Embedding runtime lost PREPARING state"
                );
            }
        });
    }

    private List<SnapshotDocument> snapshotDocuments(Authority authority) {
        leases.renew(authority);
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
                authority.migrationId()
        );
    }

    private void stageCandidate(
            Authority authority,
            SnapshotDocument snapshot,
            EmbeddingProfile target
    ) {
        long candidate = allocateCandidate(authority, snapshot, target);

        try {
            GenerationIdentity sourceIdentity = generationIdentity(
                    snapshot.documentId(),
                    snapshot.sourceGeneration()
            );
            GenerationIdentity targetIdentity = generationIdentity(
                    snapshot.documentId(),
                    candidate
            );

            List<SearchProjection> sourceProjections =
                    projections.findGeneration(sourceIdentity);
            if (sourceProjections.isEmpty()) {
                throw new IllegalStateException(
                        "Published source generation has no projections"
                );
            }

            List<SearchProjection> cloned = sourceProjections.stream()
                    .map(value -> value.withIdentity(targetIdentity))
                    .toList();
            List<DocumentIdentifier> clonedIdentifiers =
                    identifiers.findGeneration(sourceIdentity).stream()
                            .map(value -> value.withGeneration(candidate))
                            .toList();

            leases.renew(authority);
            List<float[]> embeddings = embeddingService.embed(cloned, target);
            GenerationVectorAssembler.Assembly assembly =
                    vectorAssembler.assemble(
                            cloned,
                            embeddings,
                            target,
                            candidate
                    );

            transactionTemplate.executeWithoutResult(status -> {
                leases.renew(authority);
                int owned = jdbcTemplate.update(
                        """
                        UPDATE knowledge_embedding_migration_document
                        SET updated_at = clock_timestamp()
                        WHERE migration_id = ?
                          AND document_id = ?
                          AND candidate_generation = ?
                          AND document_status = 'STAGING'
                        """,
                        authority.migrationId(),
                        snapshot.documentId(),
                        candidate
                );
                if (owned != 1) {
                    throw new IllegalStateException(
                            "Migration document lost staging authority"
                    );
                }

                projections.saveAll(targetIdentity, cloned);
                identifiers.saveAll(targetIdentity, clonedIdentifiers);
                references.cloneGeneration(sourceIdentity, targetIdentity);
                manifests.save(
                        targetIdentity,
                        target.profileId(),
                        (short) 2,
                        assembly.manifest()
                );
                vectors.insertAll(
                        target,
                        targetIdentity,
                        assembly.vectors()
                );

                int verified = jdbcTemplate.update(
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
                        authority.migrationId(),
                        snapshot.documentId(),
                        candidate
                );
                if (verified != 1) {
                    throw new IllegalStateException(
                            "Migration document could not be verified"
                    );
                }
            });
        } catch (RuntimeException exception) {
            failCandidateIfOwned(
                    authority,
                    snapshot,
                    candidate,
                    safe(exception.getMessage())
            );
            throw exception;
        }
    }

    private long allocateCandidate(
            Authority authority,
            SnapshotDocument snapshot,
            EmbeddingProfile target
    ) {
        return transactionTemplate.execute(status -> {
            leases.renew(authority);
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
                    SELECT published_generation, next_generation, access_level
                    FROM knowledge_document_lifecycle
                    WHERE document_id = ?
                    FOR UPDATE
                    """,
                    (rs, rowNum) -> new LifecycleSnapshot(
                            rs.getLong("published_generation"),
                            rs.getLong("next_generation"),
                            rs.getLong("access_level")
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
                        cleanup_required, started_at, access_level
                    ) VALUES (
                        ?, ?, 'STAGING', 'REEMBEDDING', ?, ?, ?, 2,
                        false, clock_timestamp(), ?
                    )
                    """,
                    snapshot.documentId(),
                    candidate,
                    authority.migrationId(),
                    target.profileId(),
                    fingerprint,
                    lifecycle.accessLevel()
            );
            int documentUpdated = jdbcTemplate.update(
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
                    authority.migrationId(),
                    snapshot.documentId()
            );
            if (documentUpdated != 1) {
                throw new IllegalStateException(
                        "Migration document lost SNAPSHOT state"
                );
            }
            return candidate;
        });
    }

    private void failCandidateIfOwned(
            Authority authority,
            SnapshotDocument snapshot,
            long candidate,
            String error
    ) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                leases.renew(authority);
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
                          AND migration_id = ?
                          AND generation_status = 'STAGING'
                        """,
                        error,
                        snapshot.documentId(),
                        candidate,
                        authority.migrationId()
                );
                jdbcTemplate.update(
                        """
                        UPDATE knowledge_embedding_migration_document
                        SET document_status = 'FAILED',
                            last_error = ?,
                            updated_at = clock_timestamp()
                        WHERE migration_id = ?
                          AND document_id = ?
                          AND candidate_generation = ?
                        """,
                        error,
                        authority.migrationId(),
                        snapshot.documentId(),
                        candidate
                );
            });
        } catch (LostAuthorityException ignored) {
            // A stale owner is deliberately fenced from failure cleanup.
        }
    }

    private GenerationIdentity generationIdentity(
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

    private void readyToCutover(Authority authority) {
        transactionTemplate.executeWithoutResult(status -> {
            leases.renew(authority);
            Integer pending = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*)
                    FROM knowledge_embedding_migration_document
                    WHERE migration_id = ?
                      AND document_status <> 'VERIFIED'
                    """,
                    Integer.class,
                    authority.migrationId()
            );
            if (pending != null && pending > 0) {
                throw new IllegalStateException(
                        "Not every migration document is verified"
                );
            }

            int migrationUpdated = jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_migration
                    SET migration_status = 'READY_TO_CUTOVER',
                        updated_at = clock_timestamp()
                    WHERE migration_id = ?
                      AND owner_id = ?
                      AND fencing_token = ?
                      AND migration_status = 'STAGING'
                    """,
                    authority.migrationId(),
                    authority.ownerId(),
                    authority.fencingToken()
            );
            if (migrationUpdated != 1) {
                throw new LostAuthorityException(
                        "Re-embedding migration lost ownership before cutover"
                );
            }

            int runtimeUpdated = jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_runtime
                    SET migration_status = 'READY_TO_CUTOVER',
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE singleton_id = 1
                      AND migration_status = 'STAGING'
                    """
            );
            if (runtimeUpdated != 1) {
                throw new IllegalStateException(
                        "Embedding runtime lost STAGING state"
                );
            }
        });
    }

    private void cutover(
            Authority authority,
            EmbeddingProfile source,
            EmbeddingProfile target
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            leases.renew(authority);
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

            Integer documentCount = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*)
                    FROM knowledge_embedding_migration_document
                    WHERE migration_id = ?
                    """,
                    Integer.class,
                    authority.migrationId()
            );

            Integer invalid = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*)
                    FROM knowledge_embedding_migration_document d
                    LEFT JOIN knowledge_document_lifecycle l
                      ON l.document_id = d.document_id
                    LEFT JOIN knowledge_document_generation source
                      ON source.document_id = d.document_id
                     AND source.generation = d.source_generation
                    LEFT JOIN knowledge_document_generation candidate
                      ON candidate.document_id = d.document_id
                     AND candidate.generation = d.candidate_generation
                    WHERE d.migration_id = ?
                      AND (
                          d.document_status <> 'VERIFIED'
                          OR l.document_id IS NULL
                          OR l.retention_status <> 'ACTIVE'
                          OR l.published_generation IS DISTINCT FROM d.source_generation
                          OR source.document_id IS NULL
                          OR source.generation_status <> 'PUBLISHED'
                          OR source.embedding_profile_id IS DISTINCT FROM ?
                          OR candidate.document_id IS NULL
                          OR candidate.generation_status <> 'STAGING'
                          OR candidate.embedding_profile_id IS DISTINCT FROM ?
                          OR candidate.access_level IS DISTINCT FROM l.access_level
                      )
                    """,
                    Integer.class,
                    authority.migrationId(),
                    source.profileId(),
                    target.profileId()
            );
            if (invalid != null && invalid > 0) {
                throw new IllegalStateException(
                        "Embedding migration snapshot is no longer valid"
                );
            }

            int expected = documentCount == null ? 0 : documentCount;

            List<String> lockedDocuments = jdbcTemplate.queryForList(
                    """
                    SELECT l.document_id
                    FROM knowledge_embedding_migration_document d
                    JOIN knowledge_document_lifecycle l
                      ON l.document_id = d.document_id
                    WHERE d.migration_id = ?
                    ORDER BY l.document_id
                    FOR UPDATE OF l
                    """,
                    String.class,
                    authority.migrationId()
            );
            if (lockedDocuments.size() != expected) {
                throw new IllegalStateException(
                        "Embedding cutover could not lock every lifecycle row"
                );
            }

            int retiring = jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_generation source
                    SET generation_status = 'RETIRING',
                        retired_at = clock_timestamp(),
                        cleanup_required = true,
                        last_error = NULL
                    FROM knowledge_embedding_migration_document d
                    WHERE d.migration_id = ?
                      AND source.document_id = d.document_id
                      AND source.generation = d.source_generation
                      AND source.generation_status = 'PUBLISHED'
                    """,
                    authority.migrationId()
            );
            int published = jdbcTemplate.update(
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
                    authority.migrationId()
            );
            int switched = jdbcTemplate.update(
                    """
                    UPDATE knowledge_document_lifecycle l
                    SET published_generation = d.candidate_generation,
                        generation = d.candidate_generation,
                        lifecycle_status = 'READY',
                        access_level = candidate.access_level,
                        row_version = l.row_version + 1,
                        updated_at = clock_timestamp()
                    FROM knowledge_embedding_migration_document d
                    JOIN knowledge_document_generation candidate
                      ON candidate.document_id = d.document_id
                     AND candidate.generation = d.candidate_generation
                    WHERE d.migration_id = ?
                      AND l.document_id = d.document_id
                      AND l.published_generation = d.source_generation
                    """,
                    authority.migrationId()
            );

            if (retiring != expected
                    || published != expected
                    || switched != expected) {
                throw new IllegalStateException(
                        "Embedding cutover affected unexpected row counts: expected="
                                + expected
                                + ", retiring="
                                + retiring
                                + ", published="
                                + published
                                + ", switched="
                                + switched
                );
            }

            int runtimeUpdated = jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_runtime
                    SET active_profile_id = ?,
                        migration_profile_id = NULL,
                        migration_status = 'IDLE',
                        row_version = row_version + 1,
                        updated_at = clock_timestamp()
                    WHERE singleton_id = 1
                      AND migration_status = 'READY_TO_CUTOVER'
                      AND migration_profile_id = ?
                    """,
                    target.profileId(),
                    target.profileId()
            );
            if (runtimeUpdated != 1) {
                throw new IllegalStateException(
                        "Embedding runtime could not complete cutover"
                );
            }

            int migrationUpdated = jdbcTemplate.update(
                    """
                    UPDATE knowledge_embedding_migration
                    SET migration_status = 'COMPLETED',
                        completed_at = clock_timestamp(),
                        updated_at = clock_timestamp(),
                        last_error = NULL
                    WHERE migration_id = ?
                      AND owner_id = ?
                      AND fencing_token = ?
                      AND migration_status = 'READY_TO_CUTOVER'
                    """,
                    authority.migrationId(),
                    authority.ownerId(),
                    authority.fencingToken()
            );
            if (migrationUpdated != 1) {
                throw new LostAuthorityException(
                        "Re-embedding migration lost ownership during cutover"
                );
            }
        });
    }

    private boolean abortIfOwned(Authority authority, String error) {
        try {
            Boolean aborted = transactionTemplate.execute(status -> {
                leases.renew(authority);
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
                        authority.migrationId()
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
                        authority.migrationId()
                );
                int migrationUpdated = jdbcTemplate.update(
                        """
                        UPDATE knowledge_embedding_migration
                        SET migration_status = 'FAILED',
                            last_error = ?,
                            updated_at = clock_timestamp()
                        WHERE migration_id = ?
                          AND owner_id = ?
                          AND fencing_token = ?
                          AND migration_status IN (
                              'PREPARING', 'STAGING', 'READY_TO_CUTOVER'
                          )
                        """,
                        safe(error),
                        authority.migrationId(),
                        authority.ownerId(),
                        authority.fencingToken()
                );
                if (migrationUpdated != 1) {
                    throw new LostAuthorityException(
                            "Re-embedding migration lost ownership before abort"
                    );
                }
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
                                AND migration.owner_id = ?
                                AND migration.fencing_token = ?
                                AND migration.migration_status = 'FAILED'
                          )
                        """,
                        authority.migrationId(),
                        authority.ownerId(),
                        authority.fencingToken()
                );
                return true;
            });
            return Boolean.TRUE.equals(aborted);
        } catch (LostAuthorityException ignored) {
            return false;
        }
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
            long nextGeneration,
            long accessLevel
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
