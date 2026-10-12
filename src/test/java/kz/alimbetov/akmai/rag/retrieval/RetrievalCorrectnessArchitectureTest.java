package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RetrievalCorrectnessArchitectureTest {

    @Test
    void parentExpansionPrecedesTemporalAuthorityAndContextCoordinatorOwnsSelection()
            throws Exception {
        String source = normalized(
                "src/main/java/kz/alimbetov/akmai/rag/service/RagQuestionService.java"
        );

        int parent = source.indexOf("parentcontextexpansion.expand");
        int temporal = source.indexOf("temporalauthorityfilter.filter");
        int coordinator = source.indexOf("contextselectioncoordinator.select");

        assertThat(parent).isGreaterThanOrEqualTo(0);
        assertThat(temporal).isGreaterThan(parent);
        assertThat(coordinator).isGreaterThan(temporal);
    }

    @Test
    void contextSelectionHasExactlyOneBackfillRoundAndRevalidatesWholeSecondSelection()
            throws Exception {
        String source = normalized(
                "src/main/java/kz/alimbetov/akmai/rag/retrieval/"
                        + "ContextSelectionCoordinator.java"
        );

        assertThat(source)
                .contains("max_context_selection_rounds = 2")
                .contains("secondselection")
                .contains("contextrevalidator.revalidate(secondselection, accesslevels)")
                .doesNotContain("while (")
                .doesNotContain("for (;;");
    }

    @Test
    void canonicalCandidateRepositoriesUseSharedLifecyclePredicate()
            throws Exception {
        for (String path : new String[] {
                "src/main/java/kz/alimbetov/akmai/knowledge/identifier/DocumentIdentifierRepository.java",
                "src/main/java/kz/alimbetov/akmai/knowledge/reference/ReferenceGraphRepository.java",
                "src/main/java/kz/alimbetov/akmai/knowledge/vector/PublishedVectorSearchRepository.java",
                "src/main/java/kz/alimbetov/akmai/knowledge/projection/CanonicalPostgresSearchProjectionRepository.java"
        }) {
            assertThat(normalized(path))
                    .contains("publishedlifecyclesql.eligible(\"l\")");
        }
    }

    @Test
    void keyedLookupUsesBoundedValuesBatchesInsteadOfPerKeyUnionBranches()
            throws Exception {
        String source = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/projection/"
                        + "CanonicalPostgresSearchProjectionRepository.java"
        );
        int method = source.indexOf("list<searchprojection> findpublishedbykeys");
        int lexical = source.indexOf("list<searchprojection> searchlexical");
        String keyedLookup = source.substring(method, lexical);

        assertThat(keyedLookup)
                .contains("max_keys_per_query = 256")
                .contains("with requested(")
                .contains("ordinal")
                .contains("values")
                .doesNotContain("collections.ncopies(routed.size(), branch)");
    }

    private String normalized(String path) throws Exception {
        return Files.readString(Path.of(path))
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }
}
