package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.config.IdempotencyProperties;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import kz.alimbetov.akmai.knowledge.embedding.GenerationEmbeddingService;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierType;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentGenerationRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionProperties;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository.VectorGenerationEntry;
import kz.alimbetov.akmai.knowledge.model.DocumentMetadata;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionFactory;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository.VectorRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PersistenceCoordinatorTest {

    @Test
    void buildsGenerationScopedManifestAndReservedVectorMetadata() {
        Fixture fixture = fixture();
        when(fixture.generations.allocate(
                eq("doc-1"),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile.profileId()),
                anyString(),
                eq(1L)
        )).thenReturn(7L);
        when(fixture.embeddings.embed(
                anyList(),
                eq(fixture.profile),
                any(Runnable.class)
        ))
                .thenReturn(List.of(
                        new float[] {1f, 0f, 0f},
                        new float[] {0f, 1f, 0f}
                ));
        when(fixture.publication.publish(
                eq("doc-1"),
                eq(7L),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile),
                anyList(),
                anyList(),
                anyList(),
                anyList(),
                isNull(),
                isNull()
        )).thenReturn(GenerationPublicationService.PublicationResult.PUBLISHED);

        fixture.coordinator.persist(
                List.of(
                        chunk("stable-1", "doc-1", 0),
                        chunk("stable-2", "doc-1", 1)
                ),
                null,
                null,
                1L
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VectorGenerationEntry>> manifest =
                ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VectorRow>> vectors =
                ArgumentCaptor.forClass(List.class);

        verify(fixture.publication).publish(
                eq("doc-1"),
                eq(7L),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile),
                anyList(),
                anyList(),
                manifest.capture(),
                vectors.capture(),
                isNull(),
                isNull()
        );

        assertThat(manifest.getValue())
                .extracting(VectorGenerationEntry::vectorId)
                .containsExactly(
                        VectorIdentity.physicalId("doc-1", 7L, "stable-1"),
                        VectorIdentity.physicalId("doc-1", 7L, "stable-2")
                )
                .doesNotHaveDuplicates();

        assertThat(vectors.getValue())
                .allSatisfy(row -> {
                    assertThat(row.metadata())
                            .containsEntry("akmaiGeneration", 7L)
                            .containsEntry(
                                    "akmaiEmbeddingProfileId",
                                    fixture.profile.profileId()
                            )
                            .containsEntry("akmaiMetadataVersion", 2)
                            .containsEntry(
                                    DocumentMetadata.ACCESS_LEVEL,
                                    1L
                            );
                    assertThat(row.vectorId()).matches(
                            "[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
                    );
                });
    }

    @Test
    void lostIdempotencyClaimFailsAllocatedGenerationImmediately() {
        Fixture fixture = fixture();
        when(fixture.generations.allocate(
                eq("doc-1"),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile.profileId()),
                anyString(),
                eq(1L)
        )).thenReturn(11L);
        org.mockito.Mockito.doThrow(new kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException(
                "INGESTION_IDEMPOTENCY_LOST",
                "lost"
        )).when(fixture.idempotency).attachGeneration(any(), eq(11L));

        assertThatThrownBy(() ->
                fixture.coordinator.persist(
                        List.of(chunk("stable-1", "doc-1", 0)),
                        new kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyContext(
                                "key",
                                java.util.UUID.randomUUID(),
                                "fingerprint"
                        ),
                        new kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse(
                                "doc-1",
                                1
                        ),
                        1L
                ))
                .isInstanceOf(
                        kz.alimbetov.akmai.knowledge.idempotency.IdempotencyConflictException.class
                );

        verify(fixture.generations).fail(
                eq("doc-1"),
                eq(11L),
                eq("INGESTION_IDEMPOTENCY_LOST"),
                anyString()
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
    void embeddingFailureMarksOnlyNewGenerationFailedAndNeverPublishes() {
        Fixture fixture = fixture();
        when(fixture.generations.allocate(
                eq("doc-1"),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile.profileId()),
                anyString(),
                eq(1L)
        )).thenReturn(8L);
        when(fixture.embeddings.embed(
                anyList(),
                eq(fixture.profile),
                any(Runnable.class)
        ))
                .thenThrow(new IllegalStateException("embedding unavailable"));

        assertThatThrownBy(() ->
                fixture.coordinator.persist(
                        List.of(chunk("stable-1", "doc-1", 0)),
                        null,
                        null,
                        1L
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding unavailable");

        verify(fixture.generations).fail(
                "doc-1",
                8L,
                "INGESTION_FAILED",
                "IllegalStateException: embedding unavailable"
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
                isNull(),
                isNull()
        );
    }

    @Test
    void committedPublicationRecoveredAfterAmbiguousException() {
        Fixture fixture = fixture();
        when(fixture.generations.allocate(
                eq("doc-1"),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile.profileId()),
                anyString(),
                eq(1L)
        )).thenReturn(9L);
        when(fixture.embeddings.embed(
                anyList(),
                eq(fixture.profile),
                any(Runnable.class)
        ))
                .thenReturn(List.of(new float[] {1f, 0f, 0f}));
        when(fixture.publication.publish(
                eq("doc-1"),
                eq(9L),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile),
                anyList(),
                anyList(),
                anyList(),
                anyList(),
                isNull(),
                isNull()
        )).thenThrow(new IllegalStateException("connection reset after commit"));
        when(fixture.outcomeResolver.resolve("doc-1", 9L, null))
                .thenReturn(PublicationOutcomeResolver.Outcome.COMMITTED);

        fixture.coordinator.persist(
                List.of(chunk("stable-1", "doc-1", 0)),
                null,
                null,
                1L
        );

        verify(fixture.generations, never()).fail(
                eq("doc-1"),
                eq(9L),
                anyString(),
                anyString()
        );
    }

    @Test
    void unresolvedPublicationOutcomeLeavesGenerationForRecovery() {
        Fixture fixture = fixture();
        when(fixture.generations.allocate(
                eq("doc-1"),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile.profileId()),
                anyString(),
                eq(1L)
        )).thenReturn(10L);
        when(fixture.embeddings.embed(
                anyList(),
                eq(fixture.profile),
                any(Runnable.class)
        ))
                .thenReturn(List.of(new float[] {1f, 0f, 0f}));
        when(fixture.publication.publish(
                eq("doc-1"),
                eq(10L),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile),
                anyList(),
                anyList(),
                anyList(),
                anyList(),
                isNull(),
                isNull()
        )).thenThrow(new IllegalStateException("connection reset"));
        when(fixture.outcomeResolver.resolve("doc-1", 10L, null))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> fixture.coordinator.persist(
                List.of(chunk("stable-1", "doc-1", 0)),
                null,
                null,
                1L
        ))
                .isInstanceOf(PublicationOutcomeUnknownException.class)
                .hasMessageContaining("could not be determined");

        verify(fixture.generations, never()).fail(
                eq("doc-1"),
                eq(10L),
                anyString(),
                anyString()
        );
        verify(fixture.idempotency, never()).fail(any(), anyString());
    }

    private Fixture fixture() {
        DocumentGenerationRepository generations =
                mock(DocumentGenerationRepository.class);
        EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
        GenerationEmbeddingService embeddings =
                mock(GenerationEmbeddingService.class);
        GenerationPublicationService publication =
                mock(GenerationPublicationService.class);
        PublicationOutcomeResolver outcomeResolver =
                mock(PublicationOutcomeResolver.class);
        IngestionIdempotencyRepository idempotency =
                mock(IngestionIdempotencyRepository.class);

        EmbeddingProfile profile = new EmbeddingProfile(
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
        when(profiles.activeProfile()).thenReturn(profile);

        RetentionProperties properties = new RetentionProperties(
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

        PersistenceCoordinator coordinator = new PersistenceCoordinator(
                new SearchProjectionFactory(),
                generations,
                profiles,
                embeddings,
                publication,
                outcomeResolver,
                idempotency,
                new IdempotencyProperties(Duration.ofMinutes(5)),
                properties
        );
        return new Fixture(
                coordinator,
                generations,
                embeddings,
                publication,
                outcomeResolver,
                idempotency,
                profile
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
        DetectedIdentifier identifier = new DetectedIdentifier(
                IdentifierType.DOCUMENT_NUMBER,
                "DOC-" + index,
                "DOC-" + index,
                "context"
        );
        return new EnrichedKnowledgeChunk(
                chunk,
                List.of(identifier),
                List.of()
        );
    }

    private record Fixture(
            PersistenceCoordinator coordinator,
            DocumentGenerationRepository generations,
            GenerationEmbeddingService embeddings,
            GenerationPublicationService publication,
            PublicationOutcomeResolver outcomeResolver,
            IngestionIdempotencyRepository idempotency,
            EmbeddingProfile profile
    ) {
    }
}
