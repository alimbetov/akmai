package kz.alimbetov.akmai.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import kz.alimbetov.akmai.config.IdempotencyProperties;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.chunking.AtomicUnitProtector;
import kz.alimbetov.akmai.knowledge.chunking.ChunkIdentity;
import kz.alimbetov.akmai.knowledge.chunking.ChunkingProperties;
import kz.alimbetov.akmai.knowledge.chunking.CrossReferenceExtractor;
import kz.alimbetov.akmai.knowledge.chunking.DomainSemanticClassifier;
import kz.alimbetov.akmai.knowledge.chunking.EmbeddingTextBuilder;
import kz.alimbetov.akmai.knowledge.chunking.HierarchicalChunker;
import kz.alimbetov.akmai.knowledge.chunking.OversizedUnitSplitter;
import kz.alimbetov.akmai.knowledge.chunking.ParentChildProperties;
import kz.alimbetov.akmai.knowledge.chunking.SemanticChunker;
import kz.alimbetov.akmai.knowledge.chunking.StructuralUnitExtractor;
import kz.alimbetov.akmai.knowledge.chunking.TextNormalizer;
import kz.alimbetov.akmai.knowledge.chunking.TokenEstimator;
import kz.alimbetov.akmai.knowledge.idempotency.CanonicalRequestFingerprint;
import kz.alimbetov.akmai.knowledge.idempotency.IngestionIdempotencyRepository;
import kz.alimbetov.akmai.knowledge.ingestion.EnrichedKnowledgeChunk;
import kz.alimbetov.akmai.knowledge.ingestion.ParallelIngestionExecutor;
import kz.alimbetov.akmai.knowledge.ingestion.PersistenceCoordinator;
import kz.alimbetov.akmai.knowledge.model.ChunkRole;
import kz.alimbetov.akmai.knowledge.model.KnowledgeChunk;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeIngestionLargeDocumentSmokeTest {

    @Test
    void largeStructuredDocumentTraversesRealChunkerWithoutOversizedSearchableChunks() {
        HierarchicalChunker chunker = productionLikeChunker();
        ParallelIngestionExecutor executor = mock(ParallelIngestionExecutor.class);
        PersistenceCoordinator persistence = mock(PersistenceCoordinator.class);
        when(executor.execute(anyList())).thenAnswer(invocation -> {
            List<KnowledgeChunk> chunks = invocation.getArgument(0);
            return chunks.stream()
                    .map(chunk -> new EnrichedKnowledgeChunk(
                            chunk,
                            List.of(),
                            chunk.references()
                    ))
                    .toList();
        });

        KnowledgeIngestionService service = new KnowledgeIngestionService(
                chunker,
                executor,
                persistence,
                mock(IngestionIdempotencyRepository.class),
                mock(CanonicalRequestFingerprint.class),
                new IdempotencyProperties(Duration.ofMinutes(5))
        );

        AddKnowledgeRequest request = new AddKnowledgeRequest(
                "smoke-large-legal-1",
                "Large legal smoke fixture",
                largeLegalText(),
                "smoke-fixture",
                "en",
                KnowledgeDomain.LEGAL,
                1L,
                Map.of("testKind", "large-ingestion-smoke")
        );

        var response = service.addText(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EnrichedKnowledgeChunk>> captured =
                ArgumentCaptor.forClass(List.class);
        verify(persistence).persist(
                captured.capture(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(response),
                org.mockito.ArgumentMatchers.eq(1L)
        );

        List<KnowledgeChunk> chunks = captured.getValue().stream()
                .map(EnrichedKnowledgeChunk::chunk)
                .toList();
        List<KnowledgeChunk> searchable = chunks.stream()
                .filter(chunk -> ChunkRole.isSearchable(chunk.metadata()))
                .toList();
        TokenEstimator estimator = new TokenEstimator();

        assertThat(response.documentId()).isEqualTo("smoke-large-legal-1");
        assertThat(response.chunkCount()).isEqualTo(searchable.size());
        assertThat(searchable.size()).isGreaterThan(5);
        assertThat(chunks)
                .extracting(KnowledgeChunk::chunkId)
                .doesNotHaveDuplicates();
        assertThat(searchable).allSatisfy(chunk -> {
            assertThat(chunk.rawText()).isNotBlank();
            assertThat(chunk.normalizedText()).isNotBlank();
            assertThat(chunk.embeddingText()).isNotBlank();
            assertThat(chunk.documentId()).isEqualTo("smoke-large-legal-1");
            assertThat(chunk.language()).isEqualTo("en");
            assertThat(estimator.estimate(chunk.normalizedText()))
                    .isLessThanOrEqualTo(1800);
        });
        assertThat(searchable)
                .extracting(KnowledgeChunk::sectionPath)
                .anyMatch(path -> path != null && !path.isBlank());
    }

    private HierarchicalChunker productionLikeChunker() {
        TokenEstimator estimator = new TokenEstimator();
        TextNormalizer normalizer = new TextNormalizer();
        EmbeddingTextBuilder embeddingTextBuilder = new EmbeddingTextBuilder();
        CrossReferenceExtractor references = new CrossReferenceExtractor();
        SemanticChunker semantic = new SemanticChunker(
                normalizer,
                new StructuralUnitExtractor(),
                new DomainSemanticClassifier(),
                new AtomicUnitProtector(),
                references,
                embeddingTextBuilder,
                estimator,
                new ChunkingProperties(750, 1200, 1800, 100),
                new OversizedUnitSplitter(estimator),
                new ChunkIdentity()
        );
        return new HierarchicalChunker(
                semantic,
                new ParentChildProperties(true, 250, 275, 300, true, 8),
                estimator,
                new OversizedUnitSplitter(estimator),
                embeddingTextBuilder,
                normalizer,
                references,
                new ChunkIdentity()
        );
    }

    private String largeLegalText() {
        return IntStream.rangeClosed(1, 36)
                .mapToObj(index -> """
                        Chapter %d. Operational requirements
                        Article %d. Processing and audit requirements
                        Paragraph 1. The service shall preserve document identity, publication generation, access scope, provenance and audit metadata for every operation. Contract KZ-2026-%06d remains the controlling reference for this section.
                        Paragraph 2. When processing fails, the system shall reject partial publication, retain the previous published generation and record sufficient information for deterministic recovery without exposing restricted content.
                        Paragraph 3. Concurrent workers shall not bypass lifecycle eligibility, access isolation, fencing tokens, transaction boundaries or configured resource limits. Repeated retries must remain idempotent.
                        Paragraph 4. The verification process shall confirm that all searchable fragments retain their section ancestry and that no oversized semantic unit is silently dropped during normalization or splitting.
                        """.formatted(index, index, index))
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
    }
}
