package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgvector.PGvector;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kz.alimbetov.akmai.config.ReconciliationProperties;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class GenerationRepairServiceIntegrationTest {

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
    static EmbeddingProfile profile;
    static GenerationRepairService repair;

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
        EmbeddingProfileStorageManager storage =
                new EmbeddingProfileStorageManager(jdbc);
        profile = new EmbeddingProfile(
                "ep-repair",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test",
                "repair",
                "akmai_vector",
                "p_repair",
                "NONE",
                (short) 2,
                Instant.parse("2026-10-03T00:00:00Z")
        );
        new EmbeddingProfileRepository(jdbc).save(profile);
        storage.ensureStorage(profile);

        DataSourceTransactionManager manager =
                new DataSourceTransactionManager(dataSource);
        TransactionTemplate repairTx = new TransactionTemplate(manager);
        repairTx.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        repair = new GenerationRepairService(
                jdbc,
                storage,
                new ReconciliationProperties(
                        true,
                        1,
                        1,
                        Duration.ZERO,
                        Duration.ofMinutes(5)
                ),
                repairTx
        );
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM knowledge_retired_generation");
        jdbc.update("DELETE FROM akmai_vector.p_repair");
        jdbc.update("DELETE FROM knowledge_search_projection");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");

        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id,
                    generation,
                    generation_status,
                    embedding_profile_id,
                    access_level,
                    cleanup_required,
                    retired_at
                ) VALUES (
                    'doc-repair',
                    1,
                    'RETIRED',
                    ?,
                    1,
                    true,
                    clock_timestamp() - interval '10 minutes'
                )
                """,
                profile.profileId()
        );

        for (int index = 0; index < 2; index++) {
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
                        'doc-repair',
                        1,
                        ?,
                        ?,
                        'repair text',
                        'repair text',
                        'en',
                        'GENERAL'
                    )
                    """,
                    "chunk-" + index,
                    index
            );
            jdbc.update(
                    """
                    INSERT INTO akmai_vector.p_repair (
                        access_level,
                        language,
                        document_id,
                        generation,
                        chunk_id,
                        id,
                        content,
                        metadata,
                        embedding
                    ) VALUES (
                        1,
                        'en',
                        'doc-repair',
                        1,
                        ?,
                        ?,
                        'repair text',
                        '{}'::jsonb,
                        ?
                    )
                    """,
                    "chunk-" + index,
                    UUID.nameUUIDFromBytes(
                            ("repair-" + index).getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8
                            )
                    ),
                    new PGvector(new float[] {1f, index, 0f})
            );
        }

        jdbc.update(
                """
                INSERT INTO knowledge_retired_generation (
                    document_id,
                    generation,
                    access_level,
                    embedding_profile_id,
                    projection_count,
                    vector_count,
                    physical_id_version,
                    retention_policy,
                    purge_started_at,
                    retired_at,
                    purge_after,
                    cleanup_status
                ) VALUES (
                    'doc-repair',
                    1,
                    1,
                    ?,
                    2,
                    2,
                    2,
                    'TTL',
                    clock_timestamp() - interval '11 minutes',
                    clock_timestamp() - interval '10 minutes',
                    clock_timestamp() + interval '7 days',
                    'PURGED'
                )
                """,
                profile.profileId()
        );
    }

    @Test
    void repairCommitsBoundedBatchesAndNeverMutatesTombstone() {
        GenerationIdentity identity =
                new GenerationIdentity("doc-repair", 1L, 1L);

        GenerationRepairService.RepairOutcome first =
                repair.repair(profile, identity);

        assertThat(first.batches()).isEqualTo(1);
        assertThat(first.batchLimitReached()).isTrue();
        assertThat(count("akmai_vector.p_repair")).isEqualTo(1);
        assertThat(count("knowledge_search_projection")).isEqualTo(1);
        assertThat(tombstoneStatus()).isEqualTo("PURGED");

        GenerationRepairService.RepairOutcome second =
                repair.repair(profile, identity);

        assertThat(second.batches()).isEqualTo(1);
        assertThat(count("akmai_vector.p_repair")).isZero();
        assertThat(count("knowledge_search_projection")).isZero();
        assertThat(tombstoneStatus()).isEqualTo("PURGED");
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM %s
                WHERE access_level = 1
                  AND document_id = 'doc-repair'
                  AND generation = 1
                """.formatted(table),
                Integer.class
        );
        return value == null ? 0 : value;
    }

    private String tombstoneStatus() {
        return jdbc.queryForObject(
                """
                SELECT cleanup_status
                FROM knowledge_retired_generation
                WHERE document_id = 'doc-repair'
                  AND generation = 1
                  AND access_level = 1
                """,
                String.class
        );
    }
}
