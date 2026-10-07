package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class RagBenchmarkDatasetValidatorTest {

    private static final List<String> LANGUAGES = List.of(
            "kk", "ru", "en", "zh", "de", "fr", "es", "pt", "it", "tr", "el"
    );
    private static final List<KnowledgeDomain> DOMAINS = List.of(
            KnowledgeDomain.LEGAL,
            KnowledgeDomain.MEDICAL,
            KnowledgeDomain.TECHNICAL
    );
    private static final List<RagBenchmarkDataset.QueryClass> ANSWERABLE_CLASSES =
            Arrays.stream(RagBenchmarkDataset.QueryClass.values())
                    .filter(value -> value != RagBenchmarkDataset.QueryClass.UNANSWERABLE)
                    .toList();

    @Test
    void releaseCorpusMustHaveThreeHundredQueriesAndChunkLevelTruth() {
        RagBenchmarkDataset valid = dataset(300, 75, true, true);

        var validation = RagBenchmarkDatasetValidator.validateRelease(valid);

        assertThat(validation.valid()).isTrue();
        assertThat(validation.failures()).isEmpty();
    }

    @Test
    void smokeCorpusCannotMasqueradeAsReleaseCorpus() {
        RagBenchmarkDataset smoke = dataset(11, 0, true, false);

        var validation = RagBenchmarkDatasetValidator.validateRelease(smoke);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.failures())
                .anyMatch(value -> value.contains("at least 300 queries"));
        assertThat(validation.failures())
                .anyMatch(value -> value.contains("unanswerable ratio"));
        assertThat(validation.failures())
                .anyMatch(value -> value.contains("chunk-level truth"));
    }

    private RagBenchmarkDataset dataset(
            int queryCount,
            int unanswerableCount,
            boolean releaseQualified,
            boolean chunkTruth
    ) {
        List<RagBenchmarkDataset.Document> documents = new ArrayList<>();
        List<RagBenchmarkDataset.Query> queries = new ArrayList<>();
        for (int index = 0; index < queryCount; index++) {
            String id = "case-" + index;
            String language = LANGUAGES.get(index % LANGUAGES.size());
            KnowledgeDomain domain = DOMAINS.get(index % DOMAINS.size());
            boolean answerable = index >= unanswerableCount;
            RagBenchmarkDataset.QueryClass queryClass = answerable
                    ? ANSWERABLE_CLASSES.get(index % ANSWERABLE_CLASSES.size())
                    : RagBenchmarkDataset.QueryClass.UNANSWERABLE;

            String documentId = "doc-" + index;
            documents.add(new RagBenchmarkDataset.Document(
                    documentId,
                    "Title " + index,
                    "Evidence text " + index,
                    "benchmark://" + documentId,
                    language,
                    domain,
                    1L,
                    Map.of()
            ));
            queries.add(new RagBenchmarkDataset.Query(
                    id,
                    language,
                    domain,
                    queryClass,
                    RagBenchmarkDataset.Difficulty.MEDIUM,
                    "Question " + index,
                    answerable,
                    answerable ? List.of(documentId) : List.of(),
                    answerable && chunkTruth
                            ? List.of("chunk-" + index)
                            : List.of(),
                    List.of()
            ));
        }
        return new RagBenchmarkDataset(
                "rag-benchmark-v1",
                "test-corpus",
                releaseQualified,
                documents,
                queries,
                Map.of()
        );
    }
}
