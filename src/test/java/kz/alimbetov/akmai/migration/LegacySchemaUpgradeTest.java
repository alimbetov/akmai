package kz.alimbetov.akmai.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LegacySchemaUpgradeTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    @Test
    void legacyIdentifierSurvivesRemediationUpgrade() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        migrate(dataSource, "classpath:db/changelog/legacy-pre-remediation.yaml");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, attempt_count, row_version,
                    created_at, updated_at
                ) VALUES (
                    'legacy-doc', 'PERMANENT', 'READY',
                    1, 0, 0, clock_timestamp(), clock_timestamp()
                )
                """
        );
        jdbc.update(
                """
                INSERT INTO document_identifier (
                    document_id, chunk_id, page_number, identifier_type,
                    raw_value, normalized_value, context_text, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "legacy-doc",
                "legacy-chunk",
                3,
                "DOCUMENT_NUMBER",
                "DOC-42",
                "DOC-42",
                "legacy context",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        );

        migrate(dataSource, "classpath:db/changelog/db.changelog-master.yaml");

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM legacy_document_identifier_snapshot
                WHERE row_data->>'document_id' = 'legacy-doc'
                  AND row_data->>'chunk_id' = 'legacy-chunk'
                """,
                Integer.class
        )).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM document_identifier
                WHERE document_id = 'legacy-doc'
                  AND generation = 1
                  AND chunk_id = 'legacy-chunk'
                  AND normalized_value = 'DOC-42'
                """,
                Integer.class
        )).isEqualTo(1);
    }

    private void migrate(
            PGSimpleDataSource dataSource,
            String changeLog
    ) throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLog);
        liquibase.afterPropertiesSet();
    }
}
