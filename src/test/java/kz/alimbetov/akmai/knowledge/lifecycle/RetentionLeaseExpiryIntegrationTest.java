package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.audit.AuditEventRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
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
class RetentionLeaseExpiryIntegrationTest {

    private static final UUID CLAIM_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000456");

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
        tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM knowledge_audit_event");
        jdbc.update("DELETE FROM knowledge_retired_generation");
        jdbc.update("DELETE FROM knowledge_chunk_association");
        jdbc.update("DELETE FROM knowledge_reference_edge");
        jdbc.update("DELETE FROM knowledge_reference_target");
        jdbc.update("DELETE FROM document_identifier");
        jdbc.update("DELETE FROM knowledge_search_projection");
        jdbc.update("DELETE FROM knowledge_document_vector_generation");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void leaseExpiryAfterDestructivePhaseStartsRollsBackWholeCleanup() {
        seedClaimedGeneration();

        RetentionClaimRepository claims = mock(RetentionClaimRepository.class);
        SearchProjectionRepository projections = mock(SearchProjectionRepository.class);
        DocumentIdentifierRepository identifiers =
                mock(DocumentIdentifierRepository.class);
        VectorGenerationRepository manifests =
                mock(VectorGenerationRepository.class);
        PostgresGenerationVectorRepository vectors =
                mock(PostgresGenerationVectorRepository.class);
        EmbeddingProfileRepository profiles =
                mock(EmbeddingProfileRepository.class);
        AuditEventRepository audit = mock(AuditEventRepository.class);

        EmbeddingProfile profile = new EmbeddingProfile(
                "ep-retention-expiry",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "retention-expiry",
                "akmai_vector",
                "p_retention_expiry",
                "NONE",
                (short) 2,
                Instant.parse("2026-10-12T00:00:00Z")
        );
        when(profiles.findById("ep-retention-expiry"))
                .thenReturn(Optional.of(profile));
        when(manifests.findVectorIds(any())).thenReturn(List.of());
        when(vectors.deleteGeneration(any(), any())).thenAnswer(invocation -> {
            Thread.sleep(350L);
            return 0;
        });
        when(vectors.countGeneration(any(), any())).thenReturn(0);
        when(projections.deleteGenerationCount(any())).thenReturn(0);

        ChunkRetentionService service = new ChunkRetentionService(
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
        RetentionClaim claim = new RetentionClaim(
                "doc-expiring-lease",
                1L,
                CLAIM_ID,
                "pod-a",
                Instant.now().plusSeconds(1)
        );

        RetentionCleanupResult result = service.cleanup(claim);

        assertThat(result.status())
                .isEqualTo(RetentionCleanupResult.Status.STALE_CLAIM);
        assertThat(jdbc.queryForObject(
                """
                SELECT retention_status || ':' || lifecycle_status
                FROM knowledge_document_lifecycle
                WHERE document_id = 'doc-expiring-lease'
                """,
                String.class
        )).isEqualTo("DELETE_PENDING:DELETE_PENDING");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM knowledge_retired_generation
                WHERE document_id = 'doc-expiring-lease'
                  AND generation = 1
                """,
                Integer.class
        )).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT generation_status
                FROM knowledge_document_generation
                WHERE document_id = 'doc-expiring-lease'
                  AND generation = 1
                """,
                String.class
        )).isEqualTo("PUBLISHED");
    }

    private void seedClaimedGeneration() {
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
                    'doc-expiring-lease',
                    'TTL',
                    'DELETE_PENDING',
                    1,
                    1,
                    ?,
                    'pod-a',
                    clock_timestamp(),
                    clock_timestamp() + interval '200 milliseconds',
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
                    'doc-expiring-lease',
                    1,
                    'PUBLISHED',
                    'INGESTION',
                    'ep-retention-expiry',
                    'lease-expiry-fingerprint',
                    2,
                    1,
                    0,
                    false,
                    clock_timestamp() - interval '20 minutes',
                    clock_timestamp() - interval '10 minutes'
                )
                """
        );
    }
}
