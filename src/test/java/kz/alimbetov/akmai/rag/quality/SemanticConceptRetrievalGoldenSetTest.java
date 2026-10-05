package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.semantic.ChineseSemanticMorphologyNormalizer;
import kz.alimbetov.akmai.knowledge.semantic.EnglishSemanticConceptCatalog;
import kz.alimbetov.akmai.knowledge.semantic.EnglishSemanticMorphologyNormalizer;
import kz.alimbetov.akmai.knowledge.semantic.FrenchSemanticMorphologyNormalizer;
import kz.alimbetov.akmai.knowledge.semantic.GermanSemanticMorphologyNormalizer;
import kz.alimbetov.akmai.knowledge.semantic.GreekSemanticMorphologyNormalizer;
import kz.alimbetov.akmai.knowledge.semantic.ItalianSemanticMorphologyNormalizer;
import kz.alimbetov.akmai.knowledge.semantic.KazakhSemanticMorphologyNormalizer;
import kz.alimbetov.akmai.knowledge.semantic.PortugueseSemanticMorphologyNormalizer;
import kz.alimbetov.akmai.knowledge.semantic.RussianSemanticMorphologyNormalizer;
import kz.alimbetov.akmai.knowledge.semantic.SemanticConceptMatcher;
import kz.alimbetov.akmai.knowledge.semantic.SemanticConceptSurfaceRegistry;
import kz.alimbetov.akmai.knowledge.semantic.SemanticDomainCatalog;
import kz.alimbetov.akmai.knowledge.semantic.SemanticDomainRouter;
import kz.alimbetov.akmai.knowledge.semantic.SemanticMorphologyNormalizer;
import kz.alimbetov.akmai.knowledge.semantic.SemanticMorphologyRegistry;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalyzer;
import kz.alimbetov.akmai.knowledge.semantic.SpanishSemanticMorphologyNormalizer;
import kz.alimbetov.akmai.knowledge.semantic.TurkishSemanticMorphologyNormalizer;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.query.QueryLanguageDetector;
import kz.alimbetov.akmai.rag.retrieval.ConceptRetrievalStrategy;
import kz.alimbetov.akmai.rag.retrieval.RetrievalContext;
import kz.alimbetov.akmai.rag.retrieval.RetrievalHit;
import kz.alimbetov.akmai.rag.retrieval.RetrievalTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SemanticConceptRetrievalGoldenSetTest {

    @Test
    void canonicalConceptChannelRetrievesExpectedChunkAcrossGoldenSet()
            throws IOException {
        GoldenSet golden = readGoldenSet();
        GoldenCorpus corpus = GoldenCorpus.from(golden.cases());
        ConceptRetrievalStrategy strategy = new ConceptRetrievalStrategy(
                corpus,
                analyzer(),
                RetrievalTestProperties.defaults()
        );

        List<RetrievalBenchmarkResult> results = new ArrayList<>();
        for (GoldenCase value : golden.cases()) {
            SearchProjection expected =
                    corpus.byConcept().get(value.expectedConceptId());
            QueryChunk query = new QueryChunk(
                    value.caseId(),
                    0,
                    value.query(),
                    value.query(),
                    value.query(),
                    value.language(),
                    List.of()
            );
            List<String> rankedChunkIds = strategy.retrieve(
                            query,
                            new RetrievalContext(List.of(), Set.of(1L))
                    ).stream()
                    .map(RetrievalHit::chunkId)
                    .toList();

            results.add(RetrievalBenchmarkEvaluator.evaluate(
                    new RetrievalBenchmarkCase(
                            value.caseId(),
                            value.language(),
                            value.query(),
                            Set.of(expected.chunkId())
                    ),
                    rankedChunkIds
            ));
        }

        assertThat(results).hasSize(120);
        assertThat(results)
                .allSatisfy(result ->
                        assertThat(result.recallAt5())
                                .as(result.caseId() + "/Recall@5")
                                .isEqualTo(1.0)
                );

        double meanReciprocalRank = results.stream()
                .mapToDouble(RetrievalBenchmarkResult::reciprocalRank)
                .average()
                .orElseThrow();
        assertThat(meanReciprocalRank).isGreaterThanOrEqualTo(0.75);

        Map<String, Double> recallByLanguage = results.stream()
                .collect(Collectors.groupingBy(
                        RetrievalBenchmarkResult::language,
                        Collectors.averagingDouble(
                                RetrievalBenchmarkResult::recallAt5
                        )
                ));
        assertThat(recallByLanguage)
                .containsEntry("en", 1.0)
                .containsEntry("ru", 1.0)
                .containsEntry("kk", 1.0);
    }

    private GoldenSet readGoldenSet() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (var input = new ClassPathResource(
                "quality/semantic-concept-golden-v1.json"
        ).getInputStream()) {
            return mapper.readValue(input, GoldenSet.class);
        }
    }

    private SemanticQueryAnalyzer analyzer() {
        SemanticDomainCatalog domainCatalog =
                new SemanticDomainCatalog();
        EnglishSemanticConceptCatalog conceptCatalog =
                new EnglishSemanticConceptCatalog(domainCatalog);
        SemanticMorphologyRegistry morphology =
                new SemanticMorphologyRegistry(
                        List.<SemanticMorphologyNormalizer>of(
                                new EnglishSemanticMorphologyNormalizer(),
                                new RussianSemanticMorphologyNormalizer(),
                                new KazakhSemanticMorphologyNormalizer(),
                                new ChineseSemanticMorphologyNormalizer(),
                                new GermanSemanticMorphologyNormalizer(),
                                new FrenchSemanticMorphologyNormalizer(),
                                new SpanishSemanticMorphologyNormalizer(),
                                new PortugueseSemanticMorphologyNormalizer(),
                                new ItalianSemanticMorphologyNormalizer(),
                                new TurkishSemanticMorphologyNormalizer(),
                                new GreekSemanticMorphologyNormalizer()
                        )
                );
        SemanticConceptMatcher matcher =
                new SemanticConceptMatcher(
                        new SemanticConceptSurfaceRegistry(
                                conceptCatalog,
                                morphology
                        ),
                        morphology
                );
        return new SemanticQueryAnalyzer(
                new QueryLanguageDetector(),
                matcher,
                new SemanticDomainRouter(domainCatalog)
        );
    }

    private record GoldenSet(
            String version,
            List<GoldenCase> cases
    ) {
        private GoldenSet {
            cases = List.copyOf(cases == null ? List.of() : cases);
        }
    }

    private record GoldenCase(
            String caseId,
            String language,
            String query,
            String expectedConceptId
    ) {
    }

    private record GoldenCorpus(
            Map<String, SearchProjection> byConcept
    ) implements PublishedSearchProjectionReader {

        static GoldenCorpus from(List<GoldenCase> cases) {
            LinkedHashMap<String, SearchProjection> projections =
                    new LinkedHashMap<>();
            int index = 0;
            for (GoldenCase value : cases) {
                if (projections.containsKey(value.expectedConceptId())) {
                    continue;
                }
                int chunkIndex = index++;
                projections.put(
                        value.expectedConceptId(),
                        new SearchProjection(
                                "semantic-golden-" + chunkIndex,
                                "semantic-golden-doc-" + chunkIndex,
                                1L,
                                1L,
                                null,
                                chunkIndex,
                                "Cross-language evidence chunk " + chunkIndex,
                                "Cross-language evidence chunk " + chunkIndex,
                                "zh",
                                KnowledgeDomain.GENERAL,
                                "golden",
                                List.of(),
                                List.of(),
                                Map.of(
                                        "semanticConcepts",
                                        List.of(value.expectedConceptId())
                                ),
                                2
                        )
                );
            }
            return new GoldenCorpus(Map.copyOf(projections));
        }

        @Override
        public List<SearchProjection> searchSemanticConcepts(
                List<String> conceptIds,
                List<String> documentIds,
                Set<Long> accessLevels,
                int limit
        ) {
            if (!accessLevels.contains(1L) || limit <= 0) {
                return List.of();
            }
            List<SearchProjection> result = new ArrayList<>();
            for (String conceptId : conceptIds) {
                SearchProjection projection = byConcept.get(conceptId);
                if (projection == null) {
                    continue;
                }
                if (!documentIds.isEmpty()
                        && !documentIds.contains(projection.documentId())) {
                    continue;
                }
                result.add(projection);
                if (result.size() >= limit) {
                    break;
                }
            }
            return List.copyOf(result);
        }

        @Override
        public List<SearchProjection> findByDocumentAndChunkIds(
                String documentId,
                List<String> chunkIds,
                Set<Long> accessLevels
        ) {
            return List.of();
        }

        @Override
        public List<SearchProjection> findByDocumentGenerationAndChunkIds(
                String documentId,
                long generation,
                List<String> chunkIds,
                Set<Long> accessLevels
        ) {
            return List.of();
        }

        @Override
        public List<SearchProjection> findPublishedByKeys(
                List<ProjectionKey> keys,
                Set<Long> accessLevels
        ) {
            return List.of();
        }

        @Override
        public List<SearchProjection> findAdjacent(
                String documentId,
                long generation,
                int chunkIndex,
                int radius,
                Set<Long> accessLevels
        ) {
            return List.of();
        }

        @Override
        public List<SearchProjection> searchLexical(
                String query,
                String language,
                List<String> documentIds,
                Set<Long> accessLevels,
                int limit
        ) {
            return List.of();
        }
    }
}
