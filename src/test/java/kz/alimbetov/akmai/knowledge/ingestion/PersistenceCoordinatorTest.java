package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierType;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentLifecycleRepository;
import kz.alimbetov.akmai.knowledge.lifecycle.DocumentOperationLock;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionPolicy;
import kz.alimbetov.akmai.knowledge.lifecycle.RetentionProperties;
import kz.alimbetov.akmai.knowledge.lifecycle.VectorGenerationRepository;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionFactory;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

class PersistenceCoordinatorTest {

    @Test
    void vectorDocumentsUseGenerationScopedIdsAndCanonicalChunkMetadata() {
        Fixture fixture = fixture();
        when(fixture.lifecycle.findByDocumentId("doc-1")).thenReturn(java.util.Optional.empty());
        when(fixture.lifecycle.beginIngestion("doc-1", RetentionPolicy.PERMANENT, null))
                .thenReturn(1L);
        when(fixture.lifecycle.publishIngestion(org.mockito.ArgumentMatchers.eq("doc-1"), org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(fixture.projections.findChunkIdsByDocumentId("doc-1"))
                .thenReturn(List.of());

        fixture.coordinator.persist(List.of(
                chunk("stable-1", "doc-1", 0),
                chunk("stable-2", "doc-1", 1)
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(fixture.vectorStore).add(captor.capture());

        assertThat(captor.getValue())
                .extracting(Document::getId)
                .doesNotContain("stable-1", "stable-2")
                .doesNotHaveDuplicates();
        assertThat(captor.getValue())
                .extracting(document -> document.getMetadata().get("chunkId"))
                .containsExactly("stable-1", "stable-2");
        assertThat(captor.getValue())
                .extracting(document -> document.getMetadata().get("generation"))
                .containsOnly(1L);
    }

    @Test
    void reingestionDeletesOnlyPreviousGenerationVectors() {
        Fixture fixture = fixture();
        var previous = mock(kz.alimbetov.akmai.knowledge.lifecycle.DocumentLifecycle.class);
        when(previous.generation()).thenReturn(4L);
        when(fixture.lifecycle.findByDocumentId("doc-1"))
                .thenReturn(java.util.Optional.of(previous));
        when(fixture.lifecycle.beginIngestion("doc-1", RetentionPolicy.PERMANENT, null))
                .thenReturn(5L);
        when(fixture.lifecycle.publishIngestion(org.mockito.ArgumentMatchers.eq("doc-1"), org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(fixture.vectorGenerations.findVectorIds("doc-1", 4L))
                .thenReturn(List.of("v4-a", "v4-b"));

        fixture.coordinator.persist(List.of(chunk("stable-1", "doc-1", 0)));

        verify(fixture.vectorStore).delete(List.of("v4-a", "v4-b"));
        verify(fixture.vectorGenerations).deleteGeneration("doc-1", 4L);
        verify(fixture.lifecycle).beginIngestion("doc-1", RetentionPolicy.PERMANENT, null);
    }

    private Fixture fixture() {
        VectorStore vectorStore = mock(VectorStore.class);
        IdentifierSearchIndex identifiers = mock(IdentifierSearchIndex.class);
        SearchProjectionRepository projections = mock(SearchProjectionRepository.class);
        DocumentLifecycleRepository lifecycle = mock(DocumentLifecycleRepository.class);
        VectorGenerationRepository vectorGenerations = mock(VectorGenerationRepository.class);
        DocumentOperationLock lock = mock(DocumentOperationLock.class);
        DocumentOperationLock.LockHandle handle = mock(DocumentOperationLock.LockHandle.class);
        when(lock.acquire("doc-1")).thenReturn(handle);

        RetentionProperties properties = new RetentionProperties(
                true, "0 30 3 * * *", "UTC", 100, 20, 5, 4, 16,
                Duration.ofMinutes(10), RetentionPolicy.PERMANENT, Duration.ofDays(90)
        );

        PersistenceCoordinator coordinator = new PersistenceCoordinator(
                vectorStore,
                identifiers,
                new SearchProjectionFactory(),
                projections,
                lifecycle,
                vectorGenerations,
                lock,
                properties
        );
        return new Fixture(
                coordinator, vectorStore, projections, lifecycle, vectorGenerations
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
        return new EnrichedKnowledgeChunk(chunk, List.of(identifier), List.of());
    }

    private record Fixture(
            PersistenceCoordinator coordinator,
            VectorStore vectorStore,
            SearchProjectionRepository projections,
            DocumentLifecycleRepository lifecycle,
            VectorGenerationRepository vectorGenerations
    ) {
    }
}
