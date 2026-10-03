package kz.alimbetov.akmai.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class GreenfieldSchemaBootstrapTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            ).withDatabaseName("akmai_greenfield")
                    .withUsername("akmai")
                    .withPassword("akmai");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void bootstrap() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(
                "classpath:db/changelog/greenfield/"
                        + "db.changelog-greenfield.yaml"
        );
        liquibase.afterPropertiesSet();

        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void retrievalParentsAreListPartitionedByAccessLevel() {
        for (String table : List.of(
                "knowledge_search_projection",
                "document_identifier",
                "knowledge_reference_target",
                "knowledge_reference_edge",
                "knowledge_document_vector_generation"
        )) {
            String partitionKey = jdbc.queryForObject(
                    "SELECT pg_get_partkeydef(to_regclass(?))",
                    String.class,
                    table
            );

            assertThat(partitionKey)
                    .as(table)
                    .isEqualTo("LIST (access_level)");
        }
    }

    @Test
    void projectionAccessChildrenAreListPartitionedByLanguage() {
        assertThat(jdbc.queryForObject(
                """
                SELECT pg_get_partkeydef(
                    'knowledge_search_projection_al_1'::regclass
                )
                """,
                String.class
        )).isEqualTo("LIST (language)");

        assertThat(jdbc.queryForObject(
                """
                SELECT pg_get_partkeydef(
                    'knowledge_search_projection_al_1_lang_en'::regclass
                )
                """,
                String.class
        )).isNull();

        assertThat(regclass(
                "public.knowledge_search_projection_al_1_lang_en"
        )).isNotNull();
        assertThat(regclass(
                "public.knowledge_search_projection_al_1_lang_en_s0"
        )).isNull();
        assertThat(regclass(
                "public.knowledge_search_projection_al_1_lang_en_s1"
        )).isNull();
        assertThat(regclass(
                "public.knowledge_search_projection_al_10_lang_el"
        )).isNotNull();

        Integer languages = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM akmai_supported_language
                WHERE enabled
                """,
                Integer.class
        );
        assertThat(languages).isEqualTo(12);
    }

    @Test
    void accessLevelHasNoImplicitDefault() {
        for (String table : List.of(
                "knowledge_document_lifecycle",
                "knowledge_document_generation",
                "knowledge_search_projection",
                "document_identifier",
                "knowledge_reference_target",
                "knowledge_reference_edge",
                "knowledge_document_vector_generation"
        )) {
            List<String> defaults = jdbc.query(
                    """
                    SELECT column_default
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = ?
                      AND column_name = 'access_level'
                    """,
                    (rs, rowNum) -> rs.getString(1),
                    table
            );

            assertThat(defaults)
                    .as(table)
                    .containsExactly((String) null);
        }
    }

    @Test
    void provisioningIsIdempotentAndCreatesNoDefaultPartition() {
        provision(1);
        provision(1);

        for (String child : List.of(
                "knowledge_search_projection_al_1",
                "document_identifier_al_1",
                "knowledge_reference_target_al_1",
                "knowledge_reference_edge_al_1",
                "knowledge_document_vector_generation_al_1"
        )) {
            assertThat(regclass("public." + child))
                    .as(child)
                    .isNotNull();
        }

        for (String parent : List.of(
                "knowledge_search_projection",
                "document_identifier",
                "knowledge_reference_target",
                "knowledge_reference_edge",
                "knowledge_document_vector_generation"
        )) {
            Integer defaultPartitions = jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM pg_inherits inheritance
                    JOIN pg_class child
                      ON child.oid = inheritance.inhrelid
                    WHERE inheritance.inhparent = to_regclass(?)
                      AND pg_get_expr(
                            child.relpartbound,
                            child.oid
                          ) = 'DEFAULT'
                    """,
                    Integer.class,
                    parent
            );

            assertThat(defaultPartitions)
                    .as(parent)
                    .isZero();
        }
    }

    @Test
    void generationAclForeignKeyRejectsWrongPartitionWrite() {
        provision(1);
        provision(2);
        insertGeneration("doc-fk", 1L, 1L);

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
                    'doc-fk',
                    1,
                    'chunk-ok',
                    0,
                    'text',
                    'text',
                    'en',
                    'GENERAL'
                )
                """
        );

        String physicalTable = jdbc.queryForObject(
                """
                SELECT tableoid::regclass::text
                FROM knowledge_search_projection
                WHERE access_level = 1
                  AND document_id = 'doc-fk'
                  AND generation = 1
                  AND chunk_id = 'chunk-ok'
                """,
                String.class
        );
        assertThat(physicalTable)
                .isEqualTo(
                        "knowledge_search_projection_al_1_lang_en"
                );

        assertThatThrownBy(() -> jdbc.update(
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
                    2,
                    'doc-fk',
                    1,
                    'chunk-wrong-acl',
                    1,
                    'text',
                    'text',
                    'en',
                    'GENERAL'
                )
                """
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void vectorProvisioningCreatesLocalHnswAndRoutesByAcl() {
        provision(1);
        provision(2);
        insertGeneration("doc-vector", 1L, 1L);

        jdbc.update(
                """
                INSERT INTO knowledge_embedding_profile (
                    profile_id,
                    provider,
                    model,
                    dimensions,
                    distance_type,
                    tokenizer_profile,
                    config_fingerprint,
                    vector_schema,
                    vector_table,
                    index_type,
                    storage_schema_version
                ) VALUES (
                    'profile-test',
                    'test',
                    'deterministic',
                    3,
                    'COSINE_DISTANCE',
                    'test',
                    'fingerprint',
                    'akmai_vector',
                    'test_vec',
                    'HNSW',
                    2
                )
                """
        );

        jdbc.execute(
                """
                SELECT akmai_admin.ensure_vector_profile_storage(
                    'test_vec',
                    3
                )
                """
        );

        assertThat(jdbc.queryForObject(
                """
                SELECT pg_get_partkeydef(
                    'akmai_vector.test_vec'::regclass
                )
                """,
                String.class
        )).isEqualTo("LIST (access_level)");

        assertThat(regclass("akmai_vector.test_vec_al_1"))
                .isNotNull();
        assertThat(regclass("akmai_vector.test_vec_al_2"))
                .isNotNull();
        assertThat(jdbc.queryForObject(
                """
                SELECT pg_get_partkeydef(
                    'akmai_vector.test_vec_al_1'::regclass
                )
                """,
                String.class
        )).isEqualTo("LIST (language)");
        assertThat(jdbc.queryForObject(
                """
                SELECT pg_get_partkeydef(
                    'akmai_vector.test_vec_al_1_lang_en'::regclass
                )
                """,
                String.class
        )).isNull();
        assertThat(regclass(
                "akmai_vector.test_vec_al_1_lang_en"
        )).isNotNull();
        assertThat(regclass(
                "akmai_vector.test_vec_al_1_lang_en_s0"
        )).isNull();
        assertThat(regclass(
                "akmai_vector.test_vec_al_1_lang_en_s1"
        )).isNull();

        Integer hnswIndexes = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM pg_indexes
                WHERE schemaname = 'akmai_vector'
                  AND tablename = 'test_vec_al_1_lang_en'
                  AND lower(indexdef) LIKE '%using hnsw%'
                """,
                Integer.class
        );
        assertThat(hnswIndexes).isEqualTo(1);

        jdbc.update(
                """
                INSERT INTO akmai_vector.test_vec (
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
                    'doc-vector',
                    1,
                    'chunk-1',
                    '11111111-1111-1111-1111-111111111111',
                    'text',
                    '{}'::jsonb,
                    '[1,0,0]'::vector
                )
                """
        );

        String physicalTable = jdbc.queryForObject(
                """
                SELECT tableoid::regclass::text
                FROM akmai_vector.test_vec
                WHERE id = '11111111-1111-1111-1111-111111111111'
                """,
                String.class
        );
        assertThat(physicalTable)
                .isEqualTo(
                        "akmai_vector.test_vec_al_1_lang_en"
                );

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO akmai_vector.test_vec (
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
                    2,
                    'en',
                    'doc-vector',
                    1,
                    'chunk-wrong-acl',
                    '22222222-2222-2222-2222-222222222222',
                    'text',
                    '{}'::jsonb,
                    '[1,0,0]'::vector
                )
                """
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static void provision(long accessLevel) {
        jdbc.query(
                "SELECT akmai_admin.ensure_access_level(?)",
                rs -> {
                },
                accessLevel
        );
    }

    private static void insertGeneration(
            String documentId,
            long generation,
            long accessLevel
    ) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id,
                    generation,
                    generation_status,
                    access_level
                ) VALUES (?, ?, 'STAGING', ?)
                """,
                documentId,
                generation,
                accessLevel
        );
    }

    private static String regclass(String qualifiedName) {
        return jdbc.queryForObject(
                "SELECT to_regclass(?)::text",
                String.class,
                qualifiedName
        );
    }
}
