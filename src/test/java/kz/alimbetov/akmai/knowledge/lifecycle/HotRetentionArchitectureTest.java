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
    void globalAnnFencesPublishedGenerationBeforeBranchLimit()
            throws Exception {
        String source = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/vector/"
                        + "PublishedVectorSearchRepository.java"
        );
        int globalAnn = source.indexOf(
                "private searchstatement globalann"
        );
        int documentExact = source.indexOf(
                "private searchstatement documentexact"
        );
        String method = source.substring(globalAnn, documentExact);

        int lifecycle = method.indexOf(
                "join knowledge_document_lifecycle"
        );
        int limit = method.indexOf("limit ?");

        assertThat(lifecycle).isGreaterThanOrEqualTo(0);
        assertThat(limit).isGreaterThan(lifecycle);
        assertThat(method).contains("retention_status = 'active'");
    }

    @Test
    void replacementCutoversUseRetiringBeforePayloadCleanup()
            throws Exception {
        String publication = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/ingestion/"
                        + "GenerationPublicationService.java"
        );
        assertThat(publication)
                .contains("generation_status = 'retiring'")
                .contains("cleanup_required = true");

        String reembedding = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/embedding/"
                        + "ReembeddingService.java"
        );
        int cutover = reembedding.indexOf("private void cutover");
        String cutoverMethod = reembedding.substring(cutover);
        int lifecycleLock = cutoverMethod.indexOf("for update of l");
        int generationMutation = cutoverMethod.indexOf(
                "update knowledge_document_generation source"
        );

        assertThat(lifecycleLock).isGreaterThanOrEqualTo(0);
        assertThat(generationMutation).isGreaterThan(lifecycleLock);
        assertThat(cutoverMethod)
                .contains("generation_status = 'retiring'")
                .contains("cleanup_required = true");
    }

    @Test
    void directPurgeVerifiesEveryPayloadStoreBeforePurged()
            throws Exception {
        String cleanup = normalized(
                "src/main/java/kz/alimbetov/akmai/knowledge/lifecycle/"
                        + "ChunkRetentionService.java"
        );
        int residual = cleanup.indexOf(
                "residualcounts residual = residualcounts"
        );
        int purged = cleanup.indexOf(
                "set cleanup_status = 'purged'"
        );

        assertThat(residual).isGreaterThanOrEqualTo(0);
        assertThat(purged).isGreaterThan(residual);
        assertThat(cleanup)
                .contains("knowledge_search_projection")
                .contains("document_identifier")
                .contains("knowledge_reference_target")
                .contains("knowledge_reference_edge")
                .contains("knowledge_document_vector_generation");
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
