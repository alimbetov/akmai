package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierType;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReferenceRetrievalStrategyTest {

    @Mock
    private SearchProjectionRepository repository;

    @Mock
    private IdentifierSearchIndex identifierSearchIndex;

    @Test
    void resolvesTextualReferenceToCanonicalTargetChunkInStableOrder() {
        SearchProjection seed = projection(
                "seed",
                "seed text",
                List.of("статье 48")
        );
        SearchProjection target = projection(
                "target",
                "Полный канонический текст статьи 48.",
                List.of()
        );
        DocumentIdentifier identifier = new DocumentIdentifier(
                "doc",
                "target",
                48,
                IdentifierType.DOCUMENT_NUMBER,
                "Статья 48",
                "СТАТЬЯ48",
                "короткий identifier context",
                Instant.parse("2026-01-01T00:00:00Z")
        );

        when(repository.findByChunkIds(List.of("seed")))
                .thenReturn(List.of(seed));
        when(identifierSearchIndex.search("статье 48", 10))
                .thenReturn(List.of(identifier));
        when(repository.findByChunkIds(List.of("target")))
                .thenReturn(List.of(target));

        ReferenceRetrievalStrategy subject = new ReferenceRetrievalStrategy(
                repository,
                identifierSearchIndex
        );

        List<RetrievalHit> hits = subject.retrieve(
                new QueryChunk("q", 0, "q", "q", "q", List.of()),
                new RetrievalContext(List.of(new RetrievalHit(
                        RetrievalType.LEXICAL,
                        "doc",
                        "seed",
                        "seed text",
                        Map.of()
                )))
        );

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().chunkId()).isEqualTo("target");
        assertThat(hits.getFirst().text())
                .isEqualTo("Полный канонический текст статьи 48.");
        assertThat(hits.getFirst().metadata())
                .containsEntry("expansion", "reference")
                .containsEntry("identifier", "Статья 48");
    }

    private SearchProjection projection(
            String chunkId,
            String text,
            List<String> references
    ) {
        return new SearchProjection(
                chunkId,
                "doc",
                null,
                "seed".equals(chunkId) ? 0 : 1,
                text,
                text,
                "ru",
                KnowledgeDomain.LEGAL,
                "Статья 48",
                List.of(),
                references,
                Map.of("source", "law.md"),
                1
        );
    }
}
