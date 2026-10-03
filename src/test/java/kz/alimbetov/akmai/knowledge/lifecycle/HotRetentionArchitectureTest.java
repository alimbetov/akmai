package kz.alimbetov.akmai.knowledge.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HotRetentionArchitectureTest {

    @Test
    void purgeCreatesRetirementProofBeforeDestructiveMutation()
            throws Exception {
        String source = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/lifecycle/"
                        + "ChunkRetentionService.java"
        );

        int tombstone = source.indexOf(
                "insert into knowledge_retired_generation"
        );
        int vectorDelete = source.indexOf(
                "vectorrepository.deletegeneration"
        );
        int retired = source.indexOf(
                "set generation_status = 'retired'"
        );

        assertThat(tombstone).isGreaterThanOrEqualTo(0);
        assertThat(vectorDelete).isGreaterThan(tombstone);
        assertThat(retired).isGreaterThan(vectorDelete);
        assertThat(source)
                .doesNotContain("archivegeneration")
                .doesNotContain("storage_state");
    }

    @Test
    void cleanupAndPublicationUseCompatibleControlLockOrder()
            throws Exception {
        String cleanup = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/lifecycle/"
                        + "ChunkRetentionService.java"
        );
        int lifecycleFence = cleanup.indexOf(
                "claimfence fence = lockfence(claim)"
        );
        int cleanupGeneration = cleanup.indexOf(
                "from knowledge_document_generation"
        );
        assertThat(lifecycleFence).isGreaterThanOrEqualTo(0);
        assertThat(cleanup)
                .contains("from knowledge_document_lifecycle")
                .contains("for update");
        assertThat(cleanupGeneration).isGreaterThan(lifecycleFence);

        String publication = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/ingestion/"
                        + "GenerationPublicationService.java"
        );
        int runtime = publication.indexOf(
                "from knowledge_embedding_runtime"
        );
        int lifecycle = publication.indexOf(
                "from knowledge_document_lifecycle"
        );
        int generation = publication.indexOf(
                "from knowledge_document_generation"
        );

        assertThat(runtime).isGreaterThanOrEqualTo(0);
        assertThat(lifecycle).isGreaterThan(runtime);
        assertThat(generation).isGreaterThan(lifecycle);
    }

    @Test
    void claimAndRepairPathsStayBoundedAndSeparated()
            throws Exception {
        String claims = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/lifecycle/"
                        + "RetentionClaimRepository.java"
        );
        assertThat(claims).contains("for update skip locked");

        String repair = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/lifecycle/"
                        + "GenerationRepairService.java"
        );
        assertThat(repair)
                .contains("limit ?")
                .doesNotContain(" offset ")
                .doesNotContain("knowledge_retired_generation");

        String transactions = normalized(
                "src/main/java/kz/alimbetov/akmai/config/"
                        + "TransactionTemplatesConfiguration.java"
        );
        assertThat(transactions)
                .contains("propagation_requires_new")
                .contains("repairtransactiontemplate");
    }

    private String normalized(String path) throws Exception {
        return Files.readString(Path.of(path))
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }
}
