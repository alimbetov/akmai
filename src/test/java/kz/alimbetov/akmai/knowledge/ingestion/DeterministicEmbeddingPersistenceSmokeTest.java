package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.config.IdempotencyProperties;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import kz.alimbetov.akmai.knowledge.embedding.GenerationEmbeddingService;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionProperties;
import kz.alimbetov.akmai.knowledge.model.DocumentMetadata;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionFactory;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository.VectorRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeterministicEmbeddingPersistenceSmokeTest {

    @Test
    void largeBatchProducesOneFiniteDeterministicVectorPerSearchableChunk() {
        DocumentGenerationRepository generations = mock(DocumentGenerationRepository.class);
        EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
        GenerationEmbeddingService embeddings = mock(GenerationEmbeddingService.class);
        GenerationPublicationService publication = mock(GenerationPublicationService.class);
        PublicationOutcomeResolver outcomeResolver = mock(PublicationOutcomeResolver.class);
        IngestionIdempotencyRepository idempotency = mock(IngestionIdempotencyRepository.class);

        EmbeddingProfile profile = new EmbeddingProfile(
                "ep-smoke",
                "smoke",
                "deterministic-fake",
                8,
                "COSINE_DISTANCE",
                "smoke-tokenizer",
                "smoke-fingerprint",
                "akmai_vector",
                "p_smoke",
                "NONE",
                (short) 1,
                Instant.parse("2026-10-09T00:00:00Z")
        );
        when(profiles.activeProfile()).thenReturn(profile);
        when(generations.allocate(
                eq("smoke-vector-doc"),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(profile.profileId()),
                anyString(),
                eq(1L)
        )).thenReturn(42L);
        when(embeddings.embed(
                anyList(),
                eq(profile),
                any(Runnable.class)
        )).thenAnswer(invocation -> {
            List<SearchProjection> projections = invocation.getArgument(0);
            return projections.stream()
                    .map(projection -> fakeVector(projection.embeddingText(), profile.dimension()))
                    .toList();
        });
        when(publication.publish(
                eq("smoke-vector-doc"),
                eq(42L),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(profile),
                anyList(),
                anyList(),
                anyList(),
                anyList(),
                isNull(),
                isNull()
        )).thenReturn(GenerationPublicationService.PublicationResult.PUBLISHED);

        PersistenceCoordinator coordinator = new PersistenceCoordinator(
                new SearchProjectionFactory(),
                generations,
                profiles,
                embeddings,
                publication,
                outcomeResolver,
                idempotency,
                new IdempotencyProperties(Duration.ofMinutes(5)),
                retentionProperties()
        );

        List<EnrichedKnowledgeChunk> chunks = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            KnowledgeChunk chunk = new KnowledgeChunk(
                    "chunk-" + index,
                    "smoke-vector-doc",
                    null,
                    index,
                    "Raw smoke content " + index,
                    "Normalized smoke content " + index,
                    "Embedding smoke content " + index,
                    "Smoke title",
                    "Section " + index,
                    "en",
                    KnowledgeDomain.TECHNICAL,
                    List.of(),
                    Map.of(DocumentMetadata.ACCESS_LEVEL, 1L)
            );
            chunks.add(new EnrichedKnowledgeChunk(chunk, List.of(), List.of()));
        }

        coordinator.persist(chunks, null, null, 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VectorRow>> vectors = ArgumentCaptor.forClass(List.class);
        verify(publication).publish(
                eq("smoke-vector-doc"),
                eq(42L),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(profile),
                anyList(),
                anyList(),
                anyList(),
                vectors.capture(),
                isNull(),
                isNull()
        );

        assertThat(vectors.getValue()).hasSize(64);
        assertThat(vectors.getValue())
                .extracting(VectorRow::chunkId)
                .containsExactlyElementsOf(
                        chunks.stream().map(value -> value.chunk().chunkId()).toList()
                )
                .doesNotHaveDuplicates();
        assertThat(vectors.getValue()).allSatisfy(row -> {
            assertThat(row.embedding()).hasSize(profile.dimension());
            assertThat(row.embedding()).allSatisfy(value ->
                    assertThat(Float.isFinite(value)).isTrue());
            assertThat(row.metadata())
                    .containsEntry("akmaiGeneration", 42L)
                    .containsEntry("akmaiEmbeddingProfileId", "ep-smoke")
                    .containsEntry(DocumentMetadata.ACCESS_LEVEL, 1L);
        });
        assertThat(vectors.getValue().stream()
                .map(row -> java.util.Arrays.toString(row.embedding()))
                .distinct()
                .count()).isGreaterThan(1L);
    }

    private float[] fakeVector(String text, int dimension) {
        float[] vector = new float[dimension];
        int seed = text == null ? 0 : text.hashCode();
        for (int index = 0; index < dimension; index++) {
            int mixed = Integer.rotateLeft(seed ^ (0x9E3779B9 * (index + 1)), index + 1);
            vector[index] = ((mixed & 0xffff) + 1) / 65536.0f;
        }
        return vector;
    }

    private RetentionProperties retentionProperties() {
        return new RetentionProperties(
                true,
                "0 30 3 * * *",
                "UTC",
                100,
                20,
                5,
                4,
                16,
                Duration.ofMinutes(10),
                RetentionPolicy.PERMANENT,
                Duration.ofDays(90)
        );
    }
}
