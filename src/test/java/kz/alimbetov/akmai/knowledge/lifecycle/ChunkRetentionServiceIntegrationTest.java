package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.audit.AuditEventRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.ingestion.VectorIdentity;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PostgresSearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ChunkRetentionServiceIntegrationTest {

    private static final UUID CLAIM_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000123");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static EmbeddingProfile profile;
    static EmbeddingProfileRepository profiles;
    static EmbeddingProfileStorageManager storage;
    static PostgresSearchProjectionRepository projections;
    static DocumentIdentifierRepository identifiers;
    static VectorGenerationRepository manifests;
    static PostgresGenerationVectorRepository vectors;
    static RetentionClaimRepository claims;
    static ObjectMapper mapper;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(
                "classpath:db/changelog/db.changelog-master.yaml"
        );
        liquibase.afterPropertiesSet();

        jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager manager =
                new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(manager);
        mapper = new ObjectMapper();

        storage = new EmbeddingProfileStorageManager(jdbc);
        profiles = new EmbeddingProfileRepository(jdbc);
        profile = new EmbeddingProfile(
                "ep-retention-hot",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "retention-hot",
                "akmai_vector",
                "p_retention_hot",
                "NONE",
                (short) 2,
                Instant.parse("2026-10-03T00:00:00Z")
        );
        profiles.save(profile);
        storage.ensureStorage(profile);

        projections = new PostgresSearchProjectionRepository(jdbc, mapper);
        identifiers = new DocumentIdentifierRepository(jdbc);
        manifests = new VectorGenerationRepository(jdbc);
        vectors = new PostgresGenerationVectorRepository(
                jdbc,
                mapper,
                storage
        );
        claims = new RetentionClaimRepository(jdbc, tx);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_audit_event");
        jdbc.update("DELETE FROM knowledge_retired_generation");
        jdbc.update("DELETE FROM akmai_vector.p_retention_hot");
        jdbc.update("DELETE FROM knowledge_reference_edge");
        jdbc.update("DELETE FROM knowledge_reference_target");
        jdbc.update("DELETE FROM document_identifier");
        jdbc.update("DELETE FROM knowledge_search_projection");
        jdbc.update("DELETE FROM knowledge_document_vector_generation");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void successfulPurgeCommitsPayloadProofAndLifecycleAtomically() {
        RetentionClaim claim = seedPublishedGeneration();

        RetentionCleanupResult result =
                service(new AuditEventRepository(jdbc, mapper))
                        .cleanup(claim);

        assertThat(result.status())
                .isEqualTo(RetentionCleanupResult.Status.DELETED);
        assertThat(result.deletedChunks()).isEqualTo(1);

        assertThat(payloadCount(
                "knowledge_search_projection"
        )).isZero();
        assertThat(payloadCount(
                "knowledge_document_vector_generation"
        )).isZero();
        assertThat(vectors.countGeneration(
                profile,
                identity()
        )).isZero();

        assertThat(jdbc.queryForObject(
                """
                SELECT cleanup_status
                FROM knowledge_retired_generation
                WHERE document_id = 'doc-retention'
                  AND generation = 1
                  AND access_level = 1
                """,
                String.class
        )).isEqualTo("PURGED");

        assertThat(jdbc.queryForObject(
                """
                SELECT projection_count
                       || ':' || vector_count
                FROM knowledge_retired_generation
                WHERE document_id = 'doc-retention'
                  AND generation = 1
                  AND access_level = 1
                """,
                String.class
        )).isEqualTo("1:1");

        assertThat(jdbc.queryForObject(
                """
                SELECT generation_status
                       || ':' || cleanup_required::text
                FROM knowledge_document_generation
                WHERE document_id = 'doc-retention'
                  AND generation = 1
                """,
                String.class
        )).isEqualTo("RETIRED:true");

        assertThat(jdbc.queryForObject(
                """
                SELECT lifecycle_status
                       || ':' || retention_status
                       || ':' || (published_generation IS NULL)::text
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-retention'
                """,
                String.class
        )).isEqualTo("DELETED:DELETED:true");

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_audit_event
                WHERE event_type = 'HOT_PAYLOAD_PURGED'
                  AND document_id = 'doc-retention'
                  AND generation = 1
                  AND access_level = 1
                """,
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void failureAfterPayloadDeleteRollsBackPayloadAndTombstone() {
        RetentionClaim claim = seedPublishedGeneration();
        AuditEventRepository failingAudit =
                mock(AuditEventRepository.class);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(failingAudit)
                .append(
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );

        RetentionCleanupResult result =
                service(failingAudit).cleanup(claim);

        assertThat(result.status())
                .isEqualTo(RetentionCleanupResult.Status.FAILED);

        assertThat(payloadCount(
                "knowledge_search_projection"
        )).isEqualTo(1);
        assertThat(payloadCount(
                "knowledge_document_vector_generation"
        )).isEqualTo(1);
        assertThat(vectors.countGeneration(
                profile,
                identity()
        )).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_retired_generation
                WHERE document_id = 'doc-retention'
                  AND generation = 1
                  AND access_level = 1
                """,
                Integer.class
        )).isZero();

        assertThat(jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = 'doc-retention'
                  AND generation = 1
                """,
                String.class
        )).isEqualTo("PUBLISHED");

        assertThat(jdbc.queryForObject(
                """
                SELECT retention_status
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-retention'
                """,
                String.class
        )).isEqualTo("DELETE_FAILED");
    }

    private ChunkRetentionService service(AuditEventRepository audit) {
        return new ChunkRetentionService(
                jdbc,
                tx,
                claims,
                projections,
                identifiers,
                manifests,
                vectors,
                profiles,
                audit
        );
    }

    private RetentionClaim seedPublishedGeneration() {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id,
                    lifecycle_policy,
                    lifecycle_status,
                    generation,
                    claim_generation,
                    claim_id,
                    claimed_by,
                    claimed_at,
                    lease_until,
                    expires_at,
                    attempt_count,
                    row_version,
                    retention_status,
                    published_generation,
                    next_generation,
                    access_level,
                    created_at,
                    updated_at
                ) VALUES (
                    'doc-retention',
                    'TTL',
                    'DELETE_PENDING',
                    1,
                    1,
                    ?,
                    'retention-test',
                    clock_timestamp(),
                    clock_timestamp() + interval '10 minutes',
                    clock_timestamp() - interval '1 minute',
                    0,
                    0,
                    'DELETE_PENDING',
                    1,
                    2,
                    1,
                    clock_timestamp(),
                    clock_timestamp()
                )
                """,
                CLAIM_ID
        );

        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id,
                    generation,
                    generation_status,
                    generation_kind,
                    embedding_profile_id,
                    content_fingerprint,
                    physical_id_version,
                    access_level,
                    chunk_count,
                    cleanup_required,
                    started_at,
                    published_at
                ) VALUES (
                    'doc-retention',
                    1,
                    'PUBLISHED',
                    'INGESTION',
                    ?,
                    'retention-fingerprint',
                    2,
                    1,
                    1,
                    false,
                    clock_timestamp() - interval '20 minutes',
                    clock_timestamp() - interval '10 minutes'
                )
                """,
                profile.profileId()
        );

        projections.saveAll(
                identity(),
                List.of(new SearchProjection(
                        "chunk-1",
                        "doc-retention",
                        1L,
                        1L,
                        null,
                        0,
                        "retention text",
                        "retention text",
                        "en",
                        KnowledgeDomain.GENERAL,
                        "section",
                        List.of(),
                        List.of(),
                        Map.of("source", "retention-test"),
                        2
                ))
        );

        String vectorId = VectorIdentity.physicalId(
                "doc-retention",
                1L,
                "chunk-1"
        );
        manifests.save(
                identity(),
                profile.profileId(),
                VectorIdentity.VERSION,
                List.of(new VectorGenerationRepository.VectorGenerationEntry(
                        vectorId,
                        "chunk-1"
                ))
        );
        vectors.insertAll(
                profile,
                identity(),
                List.of(new PostgresGenerationVectorRepository.VectorRow(
                        vectorId,
                        "chunk-1",
                        "en",
                        "retention text",
                        Map.of(
                                "akmaiDocumentId", "doc-retention",
                                "akmaiGeneration", 1L,
                                "akmaiChunkId", "chunk-1",
                                "language", "en"
                        ),
                        new float[] {1f, 0f, 0f}
                ))
        );

        return new RetentionClaim(
                "doc-retention",
                1L,
                CLAIM_ID,
                "retention-test",
                Instant.now().plusSeconds(600)
        );
    }

    private GenerationIdentity identity() {
        return new GenerationIdentity("doc-retention", 1L, 1L);
    }

    private int payloadCount(String table) {
        Integer value = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM %s
                WHERE access_level = 1
                  AND document_id = 'doc-retention'
                  AND generation = 1
                """.formatted(table),
                Integer.class
        );
        return value == null ? 0 : value;
    }
}
