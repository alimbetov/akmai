package kz.alimbetov.akmai.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.config.IdempotencyProperties;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.chunking.SemanticChunker;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.ingestion.ParallelIngestionExecutor;
import kz.alimbetov.akmai.knowledge.ingestion.PersistenceCoordinator;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeIngestionServiceTest {

    @Test
    void canonicalizesLanguageAliasesBeforeChunkingAndPersistence() {
        SemanticChunker chunker = mock(SemanticChunker.class);
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        when(chunker.chunk(any())).thenReturn(List.of());
        when(executor.execute(anyList())).thenReturn(List.of());

        KnowledgeIngestionService service = new KnowledgeIngestionService(
                chunker,
                executor,
                persistence,
                mock(IngestionIdempotencyRepository.class),
                mock(CanonicalRequestFingerprint.class),
                new IdempotencyProperties(Duration.ofMinutes(5))
        );

        service.addText(new AddKnowledgeRequest(
                "doc-1",
                "Title",
                "Text",
                "source",
                "RUSSIAN",
                KnowledgeDomain.GENERAL,
                7L,
                Map.of()
        ));

        ArgumentCaptor<KnowledgeDocument> captor =
                ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(chunker).chunk(captor.capture());
        assertThat(captor.getValue().language()).isEqualTo("ru");
        assertThat(captor.getValue().metadata())
                .containsEntry("access_level", 7L);
        verify(persistence).persist(
                anyList(),
                org.mockito.ArgumentMatchers.isNull(),
                any(),
                org.mockito.ArgumentMatchers.eq(7L)
        );
    }

    @Test
    void rejectsUnsupportedLanguageBeforeAnyChunkingWork() {
        SemanticChunker chunker = mock(SemanticChunker.class);
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                chunker,
                mock(ParallelIngestionExecutor.class),
                mock(PersistenceCoordinator.class),
                mock(IngestionIdempotencyRepository.class),
                mock(CanonicalRequestFingerprint.class),
                new IdempotencyProperties(Duration.ofMinutes(5))
        );

        assertThatThrownBy(() -> service.addText(new AddKnowledgeRequest(
                "doc-1",
                "Title",
                "Text",
                "source",
                "de",
                KnowledgeDomain.GENERAL,
                1L,
                Map.of()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported language");

        org.mockito.Mockito.verifyNoInteractions(chunker);
    }
}
