package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.alimbetov.akmai.config.IdempotencyProperties;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import kz.alimbetov.akmai.knowledge.embedding.GenerationEmbeddingService;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyContext;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionProperties;
import kz.alimbetov.akmai.knowledge.model.DocumentMetadata;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionFactory;
import org.junit.jupiter.api.Test;

class PersistenceCoordinatorFailureModelTest {

    @Test
    void rejectsInvalidAccessLevelBeforeAnyPersistenceWork() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.coordinator.persist(
                List.of(chunk("chunk-1", "doc-1", 0)),
                null,
                null,
                0L
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("accessLevel must be positive");

        verifyNoInteractions(
                fixture.generations,
                fixture.profiles,
                fixture.embeddings,
                fixture.publication,
                fixture.outcomeResolver,
                fixture.idempotency
        );
    }

    @Test
    void rejectsMixedDocumentBatchBeforeProfileOrGenerationWork() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.coordinator.persist(
                List.of(
                        chunk("chunk-1", "doc-1", 0),
                        chunk("chunk-2", "doc-2", 1)
                ),
                null,
                null,
                1L
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A persistence batch must contain exactly one document");

        verifyNoInteractions(
                fixture.generations,
                fixture.profiles,
                fixture.embeddings,
                fixture.publication,
                fixture.outcomeResolver,
                fixture.idempotency
        );
    }

    @Test
    void inactiveEmbeddingProfileStopsBeforeGenerationAllocation() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("configured profile is not active"))
                .when(fixture.profiles)
                .assertConfiguredProfileIsActive();

        assertThatThrownBy(() -> fixture.coordinator.persist(
                List.of(chunk("chunk-1", "doc-1", 0)),
                null,
                null,
                1L
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("configured profile is not active");

        verify(fixture.generations, never()).allocate(
                anyString(),
                any(),
                any(),
                anyString(),
                anyString(),
                any(Long.class)
        );
        verifyNoInteractions(fixture.embeddings, fixture.publication);
    }

    @Test
    void embeddingCountMismatchFailsAllocatedGenerationAndNeverPublishes() {
        Fixture fixture = fixture();
        when(fixture.generations.allocate(
                eq("doc-1"),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile.profileId()),
                anyString(),
                eq(1L)
        )).thenReturn(21L);
        when(fixture.embeddings.embed(
                anyList(),
                eq(fixture.profile),
                any(Runnable.class)
        )).thenReturn(List.of());
        IngestionIdempotencyContext context = context();

        assertThatThrownBy(() -> fixture.coordinator.persist(
                List.of(chunk("chunk-1", "doc-1", 0)),
                context,
                new KnowledgeIngestionResponse("doc-1", 1),
                1L
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Embedding count mismatch");

        verify(fixture.generations).fail(
                "doc-1",
                21L,
                "INGESTION_FAILED",
                "IllegalStateException: Embedding count mismatch"
        );
        verify(fixture.idempotency).fail(
                context,
                "IllegalStateException: Embedding count mismatch"
        );
        verify(fixture.publication, never()).publish(
                anyString(),
                any(Long.class),
                any(),
                any(),
                any(),
                anyList(),
                anyList(),
                anyList(),
                anyList(),
                any(),
                any()
        );
    }

    @Test
    void definitelyNotCommittedPublicationIsFailedAndPropagated() {
        Fixture fixture = fixture();
        when(fixture.generations.allocate(
                eq("doc-1"),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile.profileId()),
                anyString(),
                eq(1L)
        )).thenReturn(22L);
        when(fixture.embeddings.embed(
                anyList(),
                eq(fixture.profile),
                any(Runnable.class)
        )).thenReturn(List.of(new float[] {1f, 0f, 0f}));
        when(fixture.publication.publish(
                eq("doc-1"),
                eq(22L),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile),
                anyList(),
                anyList(),
                anyList(),
                anyList(),
                any(),
                any()
        )).thenThrow(new IllegalStateException("publication transaction rolled back"));
        when(fixture.outcomeResolver.resolve(eq("doc-1"), eq(22L), any()))
                .thenReturn(PublicationOutcomeResolver.Outcome.NOT_COMMITTED);
        IngestionIdempotencyContext context = context();

        assertThatThrownBy(() -> fixture.coordinator.persist(
                List.of(chunk("chunk-1", "doc-1", 0)),
                context,
                new KnowledgeIngestionResponse("doc-1", 1),
                1L
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publication transaction rolled back");

        verify(fixture.generations).fail(
                "doc-1",
                22L,
                "INGESTION_FAILED",
                "IllegalStateException: publication transaction rolled back"
        );
        verify(fixture.idempotency).fail(
                context,
                "IllegalStateException: publication transaction rolled back"
        );
    }

    @Test
    void cleanupFailuresAreSuppressedAndDoNotMaskPrimaryFailure() {
        Fixture fixture = fixture();
        when(fixture.generations.allocate(
                eq("doc-1"),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile.profileId()),
                anyString(),
                eq(1L)
        )).thenReturn(23L);
        when(fixture.embeddings.embed(
                anyList(),
                eq(fixture.profile),
                any(Runnable.class)
        )).thenThrow(new IllegalStateException("embedding unavailable"));
        doThrow(new IllegalStateException("generation cleanup unavailable"))
                .when(fixture.generations)
                .fail(eq("doc-1"), eq(23L), eq("INGESTION_FAILED"), anyString());
        doThrow(new IllegalStateException("idempotency cleanup unavailable"))
                .when(fixture.idempotency)
                .fail(any(), anyString());
        IngestionIdempotencyContext context = context();

        assertThatThrownBy(() -> fixture.coordinator.persist(
                List.of(chunk("chunk-1", "doc-1", 0)),
                context,
                new KnowledgeIngestionResponse("doc-1", 1),
                1L
        )).satisfies(exception -> {
            assertThat(exception)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("embedding unavailable");
            assertThat(exception.getSuppressed())
                    .extracting(Throwable::getMessage)
                    .containsExactly(
                            "generation cleanup unavailable",
                            "idempotency cleanup unavailable"
                    );
        });
    }

    private Fixture fixture() {
        DocumentGenerationRepository generations = mock(DocumentGenerationRepository.class);
        EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
        GenerationEmbeddingService embeddings = mock(GenerationEmbeddingService.class);
        GenerationPublicationService publication = mock(GenerationPublicationService.class);
        PublicationOutcomeResolver outcomeResolver = mock(PublicationOutcomeResolver.class);
        IngestionIdempotencyRepository idempotency = mock(IngestionIdempotencyRepository.class);
        EmbeddingProfile profile = profile();
        when(profiles.activeProfile()).thenReturn(profile);

        PersistenceCoordinator coordinator = new PersistenceCoordinator(
                new SearchProjectionFactory(),
                generations,
                profiles,
                embeddings,
                publication,
                outcomeResolver,
                idempotency,
                new IdempotencyProperties(Duration.ofMinutes(5)),
                retention()
        );
        return new Fixture(
                coordinator,
                generations,
                profiles,
                embeddings,
                publication,
                outcomeResolver,
                idempotency,
                profile
        );
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                "ep-test",
                "test",
                "deterministic",
                3,
                "COSINE_DISTANCE",
                "test-tokenizer",
                "fingerprint",
                "akmai_vector",
                "p_test",
                "NONE",
                (short) 1,
                Instant.parse("2026-10-02T00:00:00Z")
        );
    }

    private RetentionProperties retention() {
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

    private IngestionIdempotencyContext context() {
        return new IngestionIdempotencyContext(
                "key",
                UUID.randomUUID(),
                "fingerprint"
        );
    }

    private EnrichedKnowledgeChunk chunk(
            String chunkId,
            String documentId,
            int index
    ) {
        KnowledgeChunk chunk = new KnowledgeChunk(
                chunkId,
                documentId,
                null,
                index,
                "raw " + index,
                "normalized " + index,
                "embedding " + index,
                "title",
                "section",
                "en",
                KnowledgeDomain.GENERAL,
                List.of(),
                Map.of(DocumentMetadata.ACCESS_LEVEL, 1L)
        );
        return new EnrichedKnowledgeChunk(chunk, List.of(), List.of());
    }

    private record Fixture(
            PersistenceCoordinator coordinator,
            DocumentGenerationRepository generations,
            EmbeddingProfileService profiles,
            GenerationEmbeddingService embeddings,
            GenerationPublicationService publication,
            PublicationOutcomeResolver outcomeResolver,
            IngestionIdempotencyRepository idempotency,
            EmbeddingProfile profile
    ) {
    }
}
