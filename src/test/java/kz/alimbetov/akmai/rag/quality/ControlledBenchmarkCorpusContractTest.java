package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
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
import kz.alimbetov.akmai.knowledge.model.ChunkRole;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
        named = "AKMAI_CONTROLLED_BENCHMARK_CONTRACT",
        matches = "true"
)
class ControlledBenchmarkCorpusContractTest {

    @Test
    void releaseLabelsMatchActualProductionHierarchicalChunkIds() throws Exception {
        Path root = Path.of(required("AKMAI_RAG_BENCHMARK_ROOT"));
        RagBenchmarkDataset dataset = RagBenchmarkDatasetLoader.load(
                root,
                new ObjectMapper()
        );
        assertThat(RagBenchmarkDatasetValidator.validateRelease(dataset).failures())
                .isEmpty();
        assertThat(dataset.metadata())
                .containsEntry("corpusKind", "CONTROLLED_SYNTHETIC")
                .containsEntry("realWorldQualityClaim", "false");

        HierarchicalChunker chunker = chunker();
        Map<String, String> searchableByDocument = new HashMap<>();
        for (RagBenchmarkDataset.Document document : dataset.documents()) {
            KnowledgeDocument value = new KnowledgeDocument(
                    document.id(),
                    document.title(),
                    document.text(),
                    document.language(),
                    document.domain(),
                    Map.of("source", document.source())
            );
            var searchable = chunker.chunk(value).stream()
                    .filter(chunk -> ChunkRole.isSearchable(chunk.metadata()))
                    .toList();
            assertThat(searchable)
                    .as("one deterministic searchable chunk for %s", document.id())
                    .hasSize(1);
            searchableByDocument.put(
                    document.id(),
                    searchable.getFirst().chunkId()
            );
        }

        for (RagBenchmarkDataset.Query query : dataset.queries()) {
            if (!query.answerable()) {
                assertThat(query.relevantChunkIds()).isEmpty();
                continue;
            }
            assertThat(query.relevantDocumentIds()).hasSize(1);
            String documentId = query.relevantDocumentIds().getFirst();
            assertThat(query.relevantChunkIds())
                    .as("chunk truth for %s", query.id())
                    .containsExactly(searchableByDocument.get(documentId));
        }
    }

    private HierarchicalChunker chunker() {
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
                new ParentChildProperties(
                        true,
                        250,
                        275,
                        300,
                        true,
                        8
                ),
                estimator,
                new OversizedUnitSplitter(estimator),
                embeddingTextBuilder,
                normalizer,
                references,
                new ChunkIdentity()
        );
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable " + name);
        }
        return value.trim();
    }
}
