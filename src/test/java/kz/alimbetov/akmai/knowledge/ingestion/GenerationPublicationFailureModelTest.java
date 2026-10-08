package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.graph.IngestionSemanticLinker;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifierRepository;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.GenerationIdentity;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.knowledge.reference.ReferenceGraphRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository;
import kz.alimbetov.akmai.knowledge.vector.PostgresGenerationVectorRepository.VectorRow;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class GenerationPublicationFailureModelTest {

    @Test
    void ambiguousCommitResolvedAsCommittedReturnsPublished() {
        Fixture fixture = fixture();
        IllegalStateException primary =
                new IllegalStateException("commit acknowledgement lost");

        when(fixture.transactionTemplate.execute(any()))
                .thenThrow(primary);
        when(fixture.outcomeResolver.resolve("doc-1", 7L, null))
                .thenReturn(PublicationOutcomeResolver.Outcome.COMMITTED);

        assertThat(fixture.service.publish(
                "doc-1",
                7L,
                RetentionPolicy.PERMANENT,
                null,
                fixture.profile,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        )).isEqualTo(
                GenerationPublicationService.PublicationResult.PUBLISHED
        );
    }

    @Test
    void ambiguousCommitResolvedAsSupersededReturnsSuperseded() {
        Fixture fixture = fixture();

        when(fixture.transactionTemplate.execute(any()))
                .thenThrow(new IllegalStateException("commit status unknown"));
        when(fixture.outcomeResolver.resolve("doc-1", 7L, null))
                .thenReturn(PublicationOutcomeResolver.Outcome.SUPERSEDED);

        assertThat(fixture.service.publish(
                "doc-1",
                7L,
                RetentionPolicy.PERMANENT,
                null,
                fixture.profile,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        )).isEqualTo(
                GenerationPublicationService.PublicationResult.SUPERSEDED
        );
    }

    @Test
    void unresolvedPublicationRethrowsPrimaryFailure() {
        Fixture fixture = fixture();
        IllegalStateException primary = new IllegalStateException("db failure");

        when(fixture.transactionTemplate.execute(any()))
                .thenThrow(primary);
        when(fixture.outcomeResolver.resolve("doc-1", 7L, null))
                .thenReturn(PublicationOutcomeResolver.Outcome.NOT_COMMITTED);

        assertThatThrownBy(() -> fixture.service.publish(
                "doc-1",
                7L,
                RetentionPolicy.PERMANENT,
                null,
                fixture.profile,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        )).isSameAs(primary);
    }

    @Test
    void resolverFailureDoesNotMaskPrimaryPublicationFailure() {
        Fixture fixture = fixture();
        IllegalStateException primary =
                new IllegalStateException("publication transaction failed");
        IllegalStateException resolverFailure =
                new IllegalStateException("outcome read failed");

        when(fixture.transactionTemplate.execute(any()))
                .thenThrow(primary);
        when(fixture.outcomeResolver.resolve("doc-1", 7L, null))
                .thenThrow(resolverFailure);

        assertThatThrownBy(() -> fixture.service.publish(
                "doc-1",
                7L,
                RetentionPolicy.PERMANENT,
                null,
                fixture.profile,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        )).isSameAs(primary)
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .containsExactly(resolverFailure));
    }

    @Test
    void postCommitSemanticLinkFailureDoesNotChangePublishedOutcome() {
        Fixture fixture = fixture();
        IngestionSemanticLinker semanticLinker =
                mock(IngestionSemanticLinker.class);
        fixture.service.setSemanticLinker(semanticLinker);
        SearchProjection projection = projection();
        VectorRow vector = vector();

        when(fixture.transactionTemplate.execute(any()))
                .thenReturn(GenerationPublicationService.PublicationResult.PUBLISHED);
        doThrow(new IllegalStateException("graph unavailable"))
                .when(semanticLinker)
                .linkPublishedGeneration(any(GenerationIdentity.class), any());

        assertThat(fixture.service.publish(
                "doc-1",
                7L,
                RetentionPolicy.PERMANENT,
                null,
                fixture.profile,
                List.of(projection),
                List.of(),
                List.of(),
                List.of(vector)
        )).isEqualTo(
                GenerationPublicationService.PublicationResult.PUBLISHED
        );

        verify(semanticLinker).linkPublishedGeneration(
                eq(new GenerationIdentity("doc-1", 7L, 1L)),
                eq(List.of(vector))
        );
    }

    private Fixture fixture() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        SearchProjectionRepository projectionRepository =
                mock(SearchProjectionRepository.class);
        DocumentIdentifierRepository identifierRepository =
                mock(DocumentIdentifierRepository.class);
        VectorGenerationRepository vectorGenerationRepository =
                mock(VectorGenerationRepository.class);
        ReferenceGraphRepository referenceGraphRepository =
                mock(ReferenceGraphRepository.class);
        PostgresGenerationVectorRepository vectorRepository =
                mock(PostgresGenerationVectorRepository.class);
        IngestionIdempotencyRepository idempotencyRepository =
                mock(IngestionIdempotencyRepository.class);
        PublicationOutcomeResolver outcomeResolver =
                mock(PublicationOutcomeResolver.class);
        EmbeddingProfile profile = mock(EmbeddingProfile.class);

        return new Fixture(
                new GenerationPublicationService(
                        jdbcTemplate,
                        transactionTemplate,
                        projectionRepository,
                        identifierRepository,
                        vectorGenerationRepository,
                        referenceGraphRepository,
                        vectorRepository,
                        idempotencyRepository,
                        outcomeResolver
                ),
                transactionTemplate,
                outcomeResolver,
                profile
        );
    }

    private SearchProjection projection() {
        return new SearchProjection(
                "chunk-1",
                "doc-1",
                7L,
                1L,
                null,
                0,
                "text",
                "text",
                "en",
                KnowledgeDomain.GENERAL,
                "section",
                List.of(),
                List.of(),
                Map.of(),
                2
        );
    }

    private VectorRow vector() {
        return new VectorRow(
                UUID.randomUUID().toString(),
                "chunk-1",
                "en",
                "text",
                Map.of(
                        "akmaiDocumentId", "doc-1",
                        "akmaiGeneration", 7L,
                        "akmaiChunkId", "chunk-1",
                        "language", "en"
                ),
                new float[] {1f, 0f, 0f}
        );
    }

    private record Fixture(
            GenerationPublicationService service,
            TransactionTemplate transactionTemplate,
            PublicationOutcomeResolver outcomeResolver,
            EmbeddingProfile profile
    ) {
    }
}
