package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UnifiedLifecycleAuthorityArchitectureTest {

    @Test
    void distributedLifecycleAuthorityUsesPostgresTime() throws Exception {
        assertThat(normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/lifecycle/"
                        + "PublishedLifecycleSql.java"
        )).contains("clock_timestamp()")
                .doesNotContain("instant.now(")
                .doesNotContain("localdatetime.now(");

        assertThat(normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/lifecycle/"
                        + "RetentionClaimRepository.java"
        )).contains("clock_timestamp()")
                .doesNotContain("instant.now(")
                .doesNotContain("localdatetime.now(");

        assertThat(normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/graph/dream/"
                        + "DreamLeaseManager.java"
        )).contains("clock_timestamp()")
                .doesNotContain("instant.now(")
                .doesNotContain("localdatetime.now(");
    }

    @Test
    void graphReadAndMutationResolveToCanonicalLifecycleBoundary()
            throws Exception {
        String graphRead = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/graph/"
                        + "AdaptiveChunkGraphRepository.java"
        );
        assertThat(graphRead)
                .contains("publishedlifecyclesql.publishedgenerationeligible(")
                .contains("source_lifecycle")
                .contains("target_lifecycle");

        String mutationLocks = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/graph/"
                        + "GraphMutationLocks.java"
        );
        assertThat(mutationLocks)
                .contains("publishedlifecyclesql.lifecycleeligible(\"l\")")
                .contains("generation_status = 'published'")
                .contains("for share");
    }

    @Test
    void dreamCandidateBoundaryDistinguishesLifecycleFromLeaseAuthority()
            throws Exception {
        String candidates = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/graph/dream/"
                        + "DreamCandidateRepository.java"
        );
        assertThat(candidates)
                .contains("endpointineligibleexception")
                .contains("lostdreamauthorityexception")
                .contains("publishedlifecyclesql.publishedgenerationeligible(");

        String discovery = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/graph/dream/"
                        + "DreamCandidateDiscovery.java"
        );
        assertThat(discovery)
                .contains("catch (dreamcandidaterepository.endpointineligibleexception")
                .contains("return false;");
    }

    private String normalized(String path) throws Exception {
        return Files.readString(Path.of(path))
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }
}
