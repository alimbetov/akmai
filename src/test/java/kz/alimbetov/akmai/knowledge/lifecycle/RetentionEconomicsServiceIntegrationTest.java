package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgvector.PGvector;
import java.time.Instant;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RetentionEconomicsServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            ).withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static JdbcTemplate jdbc;
    static RetentionEconomicsService service;

    @BeforeAll
    static void setup() throws Exception {
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
        EmbeddingProfile profile = new EmbeddingProfile(
                "ep-economics",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "economics",
                "akmai_vector",
                "p_economics",
                "NONE",
                (short) 2,
                Instant.parse("2026-10-03T00:00:00Z")
        );
        new EmbeddingProfileRepository(jdbc).save(profile);
        new EmbeddingProfileStorageManager(jdbc).ensureStorage(profile);

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
                    retired_at
                ) VALUES (
                    'economics-doc',
                    1,
                    'RETIRED',
                    'INGESTION',
                    ?,
                    'fp-economics',
                    2,
                    1,
                    1,
                    true,
                    clock_timestamp() - interval '20 minutes',
                    clock_timestamp() - interval '10 minutes'
                )
                """,
                profile.profileId()
        );

        jdbc.update(
                """
                INSERT INTO knowledge_search_projection (
                    access_level,
                    document_id,
                    generation,
                    chunk_id,
                    chunk_index,
                    text_content,
                    embedding_text,
                    language,
                    domain
                ) VALUES (
                    1,
                    'economics-doc',
                    1,
                    'economics-chunk',
                    0,
                    'retention economics',
                    'retention economics',
                    'en',
                    'GENERAL'
                )
                """
        );

        jdbc.update(
                """
                INSERT INTO akmai_vector.p_economics (
                    access_level,
                    language,
                    document_id,
                    generation,
                    chunk_id,
                    id,
                    content,
                    metadata,
                    embedding
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                1L,
                "en",
                "economics-doc",
                1L,
                "economics-chunk",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "retention economics",
                "{}",
                new PGvector(new float[] {1f, 0f, 0f})
        );

        jdbc.update(
                """
                INSERT INTO knowledge_retired_generation (
                    document_id,
                    generation,
                    access_level,
                    embedding_profile_id,
                    projection_count,
                    vector_count,
                    content_fingerprint,
                    physical_id_version,
                    retention_policy,
                    retired_at,
                    purge_after,
                    cleanup_status
                ) VALUES (
                    'economics-doc',
                    1,
                    1,
                    ?,
                    1,
                    1,
                    'fp-economics',
                    2,
                    'TTL',
                    clock_timestamp() - interval '10 minutes',
                    clock_timestamp() + interval '7 days',
                    'PENDING_VERIFY'
                )
                """,
                profile.profileId()
        );

        jdbc.execute(
                "ANALYZE knowledge_search_projection_al_1_lang_en"
        );
        jdbc.execute(
                "ANALYZE akmai_vector.p_economics_al_1_lang_en"
        );
        service = new RetentionEconomicsService(jdbc);
    }

    @Test
    void samplesHotFootprintAndRetirementBacklogWithoutScanningPayload() {
        RetentionEconomicsSnapshot snapshot = service.snapshot();

        assertThat(snapshot.pendingGenerations()).isEqualTo(1);
        assertThat(snapshot.pendingChunks()).isEqualTo(1);
        assertThat(snapshot.oldestRetiredAgeSeconds())
                .isGreaterThanOrEqualTo(9 * 60L);
        assertThat(snapshot.pendingTombstones()).isEqualTo(1);
        assertThat(snapshot.verifiedTombstones()).isZero();
        assertThat(snapshot.tombstoneBytes()).isGreaterThan(0);

        assertThat(snapshot.projection().leafCount())
                .isGreaterThanOrEqualTo(120);
        assertThat(snapshot.projection().estimatedLiveRows())
                .isGreaterThanOrEqualTo(1);
        assertThat(snapshot.projection().totalBytes())
                .isGreaterThan(0);

        assertThat(snapshot.vector().leafCount())
                .isGreaterThanOrEqualTo(120);
        assertThat(snapshot.vector().estimatedLiveRows())
                .isGreaterThanOrEqualTo(1);
        assertThat(snapshot.vector().totalBytes())
                .isGreaterThan(0);
    }
}
