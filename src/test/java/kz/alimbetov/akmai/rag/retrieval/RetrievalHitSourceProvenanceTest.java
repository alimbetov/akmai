package kz.alimbetov.akmai.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.SourceProvenanceMetadata;
import org.junit.jupiter.api.Test;

class RetrievalHitSourceProvenanceTest {

    @Test
    void derivesTypedSourceProvenanceFromExistingMetadataPipeline() {
        RetrievalHit hit = new RetrievalHit(
                RetrievalType.VECTOR,
                1L,
                "doc-1",
                7L,
                "chunk-1",
                "text",
                Map.of(
                        SourceProvenanceMetadata.SOURCE_TYPE, "FILE",
                        SourceProvenanceMetadata.FILE_ID, "file-1",
                        SourceProvenanceMetadata.SOURCE_VERSION, "3",
                        SourceProvenanceMetadata.FILE_NAME, "document.pdf",
                        SourceProvenanceMetadata.MEDIA_TYPE, "application/pdf",
                        SourceProvenanceMetadata.CONTENT_HASH, "sha256:source",
                        "blockIds", List.of("b-1", "b-2"),
                        "pageFrom", 4,
                        "pageTo", 5,
                        "sectionPath", "Architecture > Persistence"
                )
        );

        assertThat(hit.sourceProvenance()).isNotNull();
        assertThat(hit.sourceProvenance().fileId()).isEqualTo("file-1");
        assertThat(hit.sourceProvenance().sourceVersion()).isEqualTo("3");
        assertThat(hit.sourceProvenance().blockIds())
                .containsExactly("b-1", "b-2");
        assertThat(hit.sourceProvenance().sectionPath())
                .containsExactly("Architecture", "Persistence");
    }

    @Test
    void legacyHitsWithoutFileProvenanceRemainValid() {
        RetrievalHit hit = new RetrievalHit(
                RetrievalType.LEXICAL,
                1L,
                "doc-legacy",
                1L,
                "chunk-legacy",
                "text",
                Map.of("sectionPath", "Legacy")
        );

        assertThat(hit.sourceProvenance()).isNull();
        assertThat(hit.hasRoutingIdentity()).isTrue();
    }
}
