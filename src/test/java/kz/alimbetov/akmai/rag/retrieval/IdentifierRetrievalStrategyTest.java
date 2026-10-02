package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.DocumentIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierType;
import kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchIndex;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.projection.SearchProjectionRepository;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.junit.jupiter.api.Test;

class IdentifierRetrievalStrategyTest {

    @Test
    void exactIdentifierResolvesCanonicalChunkInsteadOfIdentifierSnippet() {
        IdentifierSearchIndex index = mock(IdentifierSearchIndex.class);
        SearchProjectionRepository projections =
                mock(SearchProjectionRepository.class);
        DocumentIdentifier identifier = new DocumentIdentifier(
                "doc-1",
                3L,
                "chunk-1",
                7,
                IdentifierType.CONTRACT_NUMBER,
                "KZ-42",
                "KZ-42",
                "short identifier context",
                Instant.parse("2026-10-02T00:00:00Z")
        );
        SearchProjection projection = new SearchProjection(
                "chunk-1",
                "doc-1",
                3L,
                null,
                0,
                "FULL CANONICAL CONTRACT TEXT",
                "embedding",
                "en",
                KnowledgeDomain.LEGAL,
                "Article 25",
                List.of(),
                List.of(),
                Map.of("source", "contract.md"),
                2
        );
        when(index.search(any(kz.alimbetov.akmai.knowledge.identifier.search.IdentifierSearchQuery.class)))
                .thenReturn(List.of(identifier));
        Set<Long> scope = Set.of(3L);
        when(projections.findByDocumentAndChunkIds(
                "doc-1",
                List.of("chunk-1"),
                scope
        )).thenReturn(List.of(projection));

        IdentifierRetrievalStrategy strategy =
                new IdentifierRetrievalStrategy(
                        index,
                        projections,
                        RetrievalTestProperties.defaults()
                );
        QueryChunk query = new QueryChunk(
                "q",
                0,
                "contract KZ-42",
                "contract KZ-42",
                "contract",
                "en",
                List.of(new DetectedIdentifier(
                        IdentifierType.CONTRACT_NUMBER,
                        "KZ-42",
                        "KZ-42",
                        "contract KZ-42"
                ))
        );

        List<RetrievalHit> hits = strategy.retrieve(
                query,
                new RetrievalContext(List.of(), scope)
        );

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.text()).isEqualTo("FULL CANONICAL CONTRACT TEXT");
            assertThat(hit.text()).doesNotContain("short identifier context");
            assertThat(hit.metadata())
                    .containsEntry("authorityTier", 0)
                    .containsEntry("authority", "EXACT_IDENTIFIER")
                    .containsEntry("source", "contract.md");
        });
    }
}
