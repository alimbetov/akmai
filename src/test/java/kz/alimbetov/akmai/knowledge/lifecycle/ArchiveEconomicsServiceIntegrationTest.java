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
class ArchiveEconomicsServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            ).withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static JdbcTemplate jdbc;
    static ArchiveEconomicsService service;

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
                    storage_state,
                    domain
                ) VALUES (
                    1,
                    'economics-doc',
                    1,
                    'economics-chunk',
                    0,
                    'archive economics',
                    'archive economics',
                    'en',
                    1,
                    'GENERAL'
                )
                """
        );

        jdbc.update(
                """
                INSERT INTO akmai_vector.p_economics (
                    access_level,
                    language,
                    storage_state,
                    document_id,
                    generation,
                    chunk_id,
                    id,
                    content,
                    metadata,
                    embedding
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                1L,
                "en",
                (short) 1,
                "economics-doc",
                1L,
                "economics-chunk",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "archive economics",
                "{}",
                new PGvector(new float[] {1f, 0f, 0f})
        );

        jdbc.execute(
                "ANALYZE knowledge_search_projection_al_1_lang_en_s1"
        );
        jdbc.execute(
                "ANALYZE akmai_vector.p_economics_al_1_lang_en_s1"
        );
        service = new ArchiveEconomicsService(jdbc);
    }

    @Test
    void samplesArchiveFootprintWithoutScanningArchiveRows() {
        ArchiveEconomicsSnapshot snapshot = service.snapshot();

        assertThat(snapshot.pendingGenerations()).isEqualTo(1);
        assertThat(snapshot.pendingChunks()).isEqualTo(1);
        assertThat(snapshot.oldestRetiredAgeSeconds())
                .isGreaterThanOrEqualTo(9 * 60L);

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
