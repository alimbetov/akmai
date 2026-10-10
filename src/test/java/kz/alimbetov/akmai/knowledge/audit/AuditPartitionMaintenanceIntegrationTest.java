package kz.alimbetov.akmai.knowledge.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
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
class AuditPartitionMaintenanceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("akmai")
                    .withUsername("akmai")
                    .withPassword("akmai");

    static PGSimpleDataSource dataSource;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        dataSource = new PGSimpleDataSource();
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
    }

    @Test
    void concurrentReplicaSkipsPartitionDdlWhileAuthorityIsHeld()
            throws Exception {
        String partition = futurePartitionName();

        jdbc.execute("DROP TABLE IF EXISTS public." + partition);
        assertThat(partitionExists(partition)).isFalse();

        try (Connection owner = dataSource.getConnection()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute(
                        "SELECT pg_advisory_xact_lock(" +
                                "hashtext('akmai.audit.partition-maintenance')::bigint)"
                );
            }

            try (Connection contender = dataSource.getConnection();
                    Statement statement = contender.createStatement()) {
                statement.execute(
                        "SELECT akmai_admin.maintain_audit_partitions()"
                );
            }

            assertThat(partitionExists(partition)).isFalse();
            owner.commit();
        }

        jdbc.execute("SELECT akmai_admin.maintain_audit_partitions()");
        assertThat(partitionExists(partition)).isTrue();
    }

    @Test
    void repeatedContendersCannotBypassPartitionMaintenanceAuthority()
            throws Exception {
        String partition = futurePartitionName();

        jdbc.execute("DROP TABLE IF EXISTS public." + partition);
        assertThat(partitionExists(partition)).isFalse();

        try (Connection owner = dataSource.getConnection()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute(
                        "SELECT pg_advisory_xact_lock(" +
                                "hashtext('akmai.audit.partition-maintenance')::bigint)"
                );
            }

            for (int attempt = 0; attempt < 12; attempt++) {
                try (Connection contender = dataSource.getConnection();
                        Statement statement = contender.createStatement()) {
                    statement.execute(
                            "SELECT akmai_admin.maintain_audit_partitions()"
                    );
                }
                assertThat(partitionExists(partition)).isFalse();
            }

            owner.commit();
        }

        jdbc.execute("SELECT akmai_admin.maintain_audit_partitions()");
        assertThat(partitionExists(partition)).isTrue();
    }

    private String futurePartitionName() {
        String suffix = jdbc.queryForObject(
                """
                SELECT to_char(
                    date_trunc('month', clock_timestamp())
                        + interval '2 months',
                    'YYYY_MM'
                )
                """,
                String.class
        );
        assertThat(suffix).matches("[0-9]{4}_[0-9]{2}");
        return "knowledge_audit_event_" + suffix;
    }

    private boolean partitionExists(String partition) {
        Boolean exists = jdbc.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL",
                Boolean.class,
                "public." + partition
        );
        return Boolean.TRUE.equals(exists);
    }
}
