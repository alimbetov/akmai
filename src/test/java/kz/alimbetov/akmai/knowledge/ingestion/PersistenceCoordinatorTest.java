package kz.alimbetov.akmai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierType;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionFactory;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

class PersistenceCoordinatorTest {

    @Test
    void reingestionDeletesOldVectorAndIdentifierStateBeforeWritingReplacement() {
        VectorStore vectorStore = mock(VectorStore.class);
        IdentifierSearchIndex identifiers = mock(IdentifierSearchIndex.class);
        SearchProjectionRepository projections = mock(SearchProjectionRepository.class);
        when(projections.findChunkIdsByDocumentId("doc-1"))
                .thenReturn(List.of("old-a", "old-b"));

        PersistenceCoordinator coordinator = new PersistenceCoordinator(
                vectorStore,
                identifiers,
                new SearchProjectionFactory(),
                projections
        );

        coordinator.persist(List.of(chunk("new-a", "doc-1", 0)), 2);

        InOrder order = inOrder(vectorStore, identifiers, projections);
        order.verify(projections).findChunkIdsByDocumentId("doc-1");
        order.verify(vectorStore).delete(List.of("old-a", "old-b"));
        order.verify(identifiers).deleteByDocumentId("doc-1");
        order.verify(projections).deleteByDocumentId("doc-1");
        order.verify(projections).saveAll(anyList());
        order.verify(vectorStore).add(anyList());
        order.verify(identifiers).index(anyList());
    }

    @Test
    void vectorDocumentsUseCanonicalChunkIds() {
        VectorStore vectorStore = mock(VectorStore.class);
        IdentifierSearchIndex identifiers = mock(IdentifierSearchIndex.class);
        SearchProjectionRepository projections = mock(SearchProjectionRepository.class);
        when(projections.findChunkIdsByDocumentId("doc-1"))
                .thenReturn(List.of());

        PersistenceCoordinator coordinator = new PersistenceCoordinator(
                vectorStore,
                identifiers,
                new SearchProjectionFactory(),
                projections
        );

        coordinator.persist(List.of(
                chunk("stable-1", "doc-1", 0),
                chunk("stable-2", "doc-1", 1)
        ), 7);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        assertThat(captor.getValue())
                .extracting(Document::getId)
                .containsExactly(
                        "doc-1::g7::stable-1",
                        "doc-1::g7::stable-2"
                );
        assertThat(captor.getValue())
                .extracting(document -> document.getMetadata().get("chunkId"))
                .containsExactly("stable-1", "stable-2");
        assertThat(captor.getValue())
                .extracting(document -> document.getMetadata().get("generation"))
                .containsOnly(7L);
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
}
