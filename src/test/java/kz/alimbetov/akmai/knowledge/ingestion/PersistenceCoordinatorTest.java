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
                anyString()
        )).thenReturn(7L);
        when(fixture.embeddings.embed(anyList(), eq(fixture.profile)))
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
                anyList()
        )).thenReturn(GenerationPublicationService.PublicationResult.PUBLISHED);

        fixture.coordinator.persist(List.of(
                chunk("stable-1", "doc-1", 0),
                chunk("stable-2", "doc-1", 1)
        ));

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
                vectors.capture()
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
                            .containsEntry("akmaiMetadataVersion", 2);
                    assertThat(row.vectorId()).matches(
                            "[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
                    );
                });
    }

    @Test
    void embeddingFailureMarksOnlyNewGenerationFailedAndNeverPublishes() {
        Fixture fixture = fixture();
        when(fixture.generations.allocate(
                eq("doc-1"),
                eq(RetentionPolicy.PERMANENT),
                isNull(),
                eq(fixture.profile.profileId()),
                anyString()
        )).thenReturn(8L);
        when(fixture.embeddings.embed(anyList(), eq(fixture.profile)))
                .thenThrow(new IllegalStateException("embedding unavailable"));

        assertThatThrownBy(() ->
                fixture.coordinator.persist(List.of(
                        chunk("stable-1", "doc-1", 0)
                )))
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
                anyList()
        );
    }

    private Fixture fixture() {
        DocumentGenerationRepository generations =
                mock(DocumentGenerationRepository.class);
        EmbeddingProfileService profiles = mock(EmbeddingProfileService.class);
        GenerationEmbeddingService embeddings =
                mock(GenerationEmbeddingService.class);
        GenerationPublicationService publication =
                mock(GenerationPublicationService.class);
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
                idempotency,
                properties
        );
        return new Fixture(
                coordinator,
                generations,
                embeddings,
                publication,
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
                Map.of()
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
            EmbeddingProfile profile
    ) {
    }
}
