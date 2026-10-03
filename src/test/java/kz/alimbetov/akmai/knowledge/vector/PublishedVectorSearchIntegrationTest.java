package kz.alimbetov.akmai.knowledge.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileRepository;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileStorageManager;
import kz.alimbetov.akmai.knowledge.ingestion.VectorIdentity;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PublishedVectorSearchIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres")
            ).withDatabaseName("akmai").withUsername("akmai").withPassword("akmai");

    static JdbcTemplate jdbc;
    static EmbeddingProfile profile;
    static PostgresGenerationVectorRepository vectors;
    static PublishedVectorSearchRepository search;

    @BeforeAll
    static void setup() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());

        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(ds);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();

        jdbc = new JdbcTemplate(ds);
        jdbc.execute("SELECT akmai_admin.ensure_access_level(2)");
        jdbc.execute("SELECT akmai_admin.ensure_access_level(3)");
        ObjectMapper mapper = new ObjectMapper();
        EmbeddingProfileStorageManager storage =
                new EmbeddingProfileStorageManager(jdbc);
        profile = new EmbeddingProfile(
                "ep-search", "test", "deterministic", 3,
                "COSINE_DISTANCE", "test", "search",
                "akmai_vector", "p_search", "HNSW", (short) 1,
                Instant.parse("2026-10-02T00:00:00Z")
        );
        storage.ensureStorage(profile);
        new EmbeddingProfileRepository(jdbc).save(profile);
        jdbc.update(
                "UPDATE knowledge_embedding_runtime SET active_profile_id = ?, migration_status = 'IDLE' WHERE singleton_id = 1",
                profile.profileId()
        );
        vectors = new PostgresGenerationVectorRepository(jdbc, mapper, storage);

        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed("query")).thenReturn(new float[] {1f, 0f, 0f});
        EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
        when(profiles.activeProfile()).thenReturn(profile);
        search = new PublishedVectorSearchRepository(
                jdbc,
                mapper,
                model,
                profiles,
                storage,
                new TransactionTemplate(new DataSourceTransactionManager(ds))
        );
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM akmai_vector.p_search");
        jdbc.update("DELETE FROM knowledge_document_generation");
        jdbc.update("DELETE FROM knowledge_document_lifecycle");
    }

    @Test
    void filtersStaleScopeAndLowSimilarityBeforeLimit() {
        lifecycle("doc-1", 2L, 1L);
        lifecycle("doc-2", 1L, 2L);
        generation("doc-1", 1L, "RETIRED");
        generation("doc-1", 2L, "PUBLISHED");
        generation("doc-2", 1L, "PUBLISHED");

        vectors.insertAll(profile, List.of(
                row("doc-1", 1L, "stale", new float[] {1f, 0f, 0f}),
                row("doc-1", 2L, "published", new float[] {1f, 0f, 0f}),
                row("doc-1", 2L, "low", new float[] {0f, 1f, 0f}),
                row("doc-2", 1L, "other", new float[] {1f, 0f, 0f})
        ));

        List<VectorSearchMatch> result =
                search.search(
                        "query",
                        List.of("doc-1"),
                        Set.of(1L),
                        1,
                        0.8
                );

        assertThat(result)
                .extracting(VectorSearchMatch::chunkId)
                .containsExactly("published");
        assertThat(result.getFirst().generation()).isEqualTo(2L);
        assertThat(result.getFirst().score()).isGreaterThanOrEqualTo(0.99);
    }

    @Test
    void vectorSearchReturnsOnlyAuthorizedAccessLevels() {
        lifecycle("access-1", 1L, 1L);
        lifecycle("access-2", 1L, 2L);
        lifecycle("access-3", 1L, 3L);
        generation("access-1", 1L, "PUBLISHED");
        generation("access-2", 1L, "PUBLISHED");
        generation("access-3", 1L, "PUBLISHED");

        vectors.insertAll(profile, List.of(
                row("access-1", 1L, "chunk-1", new float[] {1f, 0f, 0f}),
                row("access-2", 1L, "chunk-2", new float[] {1f, 0f, 0f}),
                row("access-3", 1L, "chunk-3", new float[] {1f, 0f, 0f})
        ));

        assertThat(search.search(
                "query",
                List.of(),
                Set.of(1L, 2L),
                10,
                0.8
        )).extracting(VectorSearchMatch::documentId)
                .containsExactlyInAnyOrder("access-1", "access-2");

        assertThat(search.search(
                "query",
                List.of(),
                Set.of(3L),
                10,
                0.8
        )).extracting(VectorSearchMatch::documentId)
                .containsExactly("access-3");

        assertThatThrownBy(() -> search.search(
                "query",
                List.of(),
                Set.of(),
                10,
                0.8
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessLevels");

        assertThat(search.search(
                "query",
                List.of(),
                Set.of(99L),
                10,
                0.8
        )).isEmpty();
    }

    @Test
    void sameLanguageSearchUsesLocalLeafThenFallsBackCrossLanguage() {
        lifecycle("en-doc", 1L, 1L);
        lifecycle("ru-doc", 1L, 1L);
        generation("en-doc", 1L, "PUBLISHED");
        generation("ru-doc", 1L, "PUBLISHED");

        PostgresGenerationVectorRepository.VectorRow en =
                new PostgresGenerationVectorRepository.VectorRow(
                        VectorIdentity.physicalId(
                                "en-doc",
                                1L,
                                "en-chunk"
                        ),
                        "en-chunk",
                        "en",
                        "en-content",
                        Map.of(
                                "akmaiMetadataVersion", 2,
                                "akmaiDocumentId", "en-doc",
                                "akmaiGeneration", 1L,
                                "akmaiEmbeddingProfileId",
                                profile.profileId(),
                                "akmaiChunkId", "en-chunk",
                                "language", "en"
                        ),
                        new float[] {1f, 0f, 0f}
                );
        PostgresGenerationVectorRepository.VectorRow ru =
                new PostgresGenerationVectorRepository.VectorRow(
                        VectorIdentity.physicalId(
                                "ru-doc",
                                1L,
                                "ru-chunk"
                        ),
                        "ru-chunk",
                        "ru",
                        "ru-content",
                        Map.of(
                                "akmaiMetadataVersion", 2,
                                "akmaiDocumentId", "ru-doc",
                                "akmaiGeneration", 1L,
                                "akmaiEmbeddingProfileId",
                                profile.profileId(),
                                "akmaiChunkId", "ru-chunk",
                                "language", "ru"
                        ),
                        new float[] {1f, 0f, 0f}
                );

        vectors.insertAll(profile, List.of(en, ru));

        assertThat(search.search(
                "query",
                "en",
                List.of(),
                Set.of(1L),
                1,
                0.8
        )).extracting(VectorSearchMatch::documentId)
                .containsExactly("en-doc");

        assertThat(search.search(
                "query",
                "en",
                List.of(),
                Set.of(1L),
                2,
                0.8
        )).extracting(VectorSearchMatch::documentId)
                .containsExactlyInAnyOrder("en-doc", "ru-doc");
    }

    @Test
    void hnswIterativeScanFillsTopKUnderSelectiveAccessFilter() {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, attempt_count, row_version,
                    created_at, updated_at, retention_status,
                    published_generation, next_generation, access_level
                )
                SELECT
                    'hnsw-doc-' || value,
                    'PERMANENT',
                    'READY',
                    1,
                    0,
                    0,
                    clock_timestamp(),
                    clock_timestamp(),
                    'ACTIVE',
                    1,
                    2,
                    CASE WHEN value <= 50 THEN 1 ELSE 2 END
                FROM generate_series(1, 1000) AS value
                """
        );
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id, generation, generation_status, generation_kind,
                    embedding_profile_id, content_fingerprint,
                    physical_id_version, cleanup_required, started_at,
                    published_at, access_level
                )
                SELECT
                    'hnsw-doc-' || value,
                    1,
                    'PUBLISHED',
                    'INGESTION',
                    ?,
                    'fp-' || value,
                    2,
                    false,
                    clock_timestamp(),
                    clock_timestamp(),
                    CASE WHEN value <= 50 THEN 1 ELSE 2 END
                FROM generate_series(1, 1000) AS value
                """,
                profile.profileId()
        );

        vectors.insertAll(
                profile,
                java.util.stream.IntStream.rangeClosed(1, 1000)
                        .mapToObj(value -> row(
                                "hnsw-doc-" + value,
                                1L,
                                "chunk-" + value,
                                new float[] {1f, 0f, 0f}
                        ))
                        .toList()
        );
        jdbc.execute("ANALYZE akmai_vector.p_search");

        List<VectorSearchMatch> result = search.search(
                "query",
                List.of(),
                Set.of(1L),
                10,
                0.8
        );

        assertThat(result).hasSize(10);
        assertThat(result)
                .allSatisfy(match -> assertThat(match.documentId())
                        .startsWith("hnsw-doc-"));
        assertThat(result)
                .extracting(VectorSearchMatch::documentId)
                .allSatisfy(documentId -> {
                    int id = Integer.parseInt(
                            documentId.substring("hnsw-doc-".length())
                    );
                    assertThat(id).isBetween(1, 50);
                });
    }

    private void lifecycle(
            String documentId,
            long published,
            long accessLevel
    ) {
        jdbc.update(
                """
                INSERT INTO knowledge_document_lifecycle (
                    document_id, lifecycle_policy, lifecycle_status,
                    generation, attempt_count, row_version,
                    created_at, updated_at, retention_status,
                    published_generation, next_generation, access_level
                ) VALUES (?, 'PERMANENT', 'READY', ?, 0, 0,
                          clock_timestamp(), clock_timestamp(), 'ACTIVE', ?, ?, ?)
                """,
                documentId, published, published, published + 1, accessLevel
        );
    }

    private void generation(
            String documentId,
            long generation,
            String status
    ) {
        Long accessLevel = jdbc.queryForObject(
                """
                SELECT access_level
                FROM knowledge_document_lifecycle
                WHERE document_id = ?
                """,
                Long.class,
                documentId
        );
        jdbc.update(
                """
                INSERT INTO knowledge_document_generation (
                    document_id, generation, generation_status,
                    generation_kind, embedding_profile_id,
                    content_fingerprint, physical_id_version,
                    cleanup_required, started_at, published_at,
                    retired_at, access_level
                ) VALUES (
                    ?, ?, ?, 'INGESTION', ?, 'fp', 2, false,
                    clock_timestamp() - interval '1 hour',
                    CASE WHEN ? = 'PUBLISHED'
                        THEN clock_timestamp() ELSE NULL END,
                    CASE WHEN ? = 'RETIRED'
                        THEN clock_timestamp() ELSE NULL END,
                    ?
                )
                """,
                documentId,
                generation,
                status,
                profile.profileId(),
                status,
                status,
                accessLevel
        );
    }

    private PostgresGenerationVectorRepository.VectorRow row(
            String documentId, long generation, String chunkId, float[] embedding
    ) {
        return new PostgresGenerationVectorRepository.VectorRow(
                VectorIdentity.physicalId(documentId, generation, chunkId),
                chunkId,
                "en",
                Map.of(
                        "akmaiMetadataVersion", 2,
                        "akmaiDocumentId", documentId,
                        "akmaiGeneration", generation,
                        "akmaiEmbeddingProfileId", profile.profileId(),
                        "akmaiChunkId", chunkId,
                        "language", "en"
                ),
                embedding
        );
    }
}
