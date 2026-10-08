package kz.alimbetov.akmai.knowledge.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class CanonicalDocumentValidationTest {

    @Test
    void rejectsBlankRequiredDocumentFields() {
        assertThatThrownBy(() -> document(" ", 1L, List.of(block())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("documentId must not be blank");

        assertThatThrownBy(() -> new CanonicalDocument(
                "doc-1",
                " ",
                "Title",
                "source",
                "en",
                KnowledgeDomain.GENERAL,
                1L,
                List.of(block()),
                Map.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("version must not be blank");
    }

    @Test
    void rejectsMissingDomainAndNonPositiveAccessLevel() {
        assertThatThrownBy(() -> new CanonicalDocument(
                "doc-1",
                "v1",
                "Title",
                "source",
                "en",
                null,
                1L,
                List.of(block()),
                Map.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("domain is required");

        assertThatThrownBy(() -> document("doc-1", 0L, List.of(block())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("accessLevel must be positive");
    }

    @Test
    void rejectsNullOrEmptyBlocks() {
        assertThatThrownBy(() -> document("doc-1", 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("canonical document must contain blocks");

        assertThatThrownBy(() -> document("doc-1", 1L, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("canonical document must contain blocks");
    }

    @Test
    void rejectsInvalidHeadingLevel() {
        assertThatThrownBy(() -> new CanonicalDocument.Block(
                "block-1",
                CanonicalDocument.BlockType.HEADING,
                "Heading",
                0,
                1,
                1,
                "Section",
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("headingLevel must be between 1 and 7");

        assertThatThrownBy(() -> new CanonicalDocument.Block(
                "block-1",
                CanonicalDocument.BlockType.HEADING,
                "Heading",
                8,
                1,
                1,
                "Section",
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("headingLevel must be between 1 and 7");
    }

    @Test
    void rejectsInvalidPageRange() {
        assertThatThrownBy(() -> new CanonicalDocument.Block(
                "block-1",
                CanonicalDocument.BlockType.PARAGRAPH,
                "Text",
                null,
                0,
                1,
                "Section",
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pageFrom must be positive");

        assertThatThrownBy(() -> new CanonicalDocument.Block(
                "block-1",
                CanonicalDocument.BlockType.PARAGRAPH,
                "Text",
                null,
                3,
                2,
                "Section",
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pageTo must be >= pageFrom");
    }

    @Test
    void rejectsInvalidBoundingBox() {
        assertThatThrownBy(() -> new CanonicalDocument.BoundingBox(
                -1.0,
                0.0,
                10.0,
                10.0
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite and non-negative");

        assertThatThrownBy(() -> new CanonicalDocument.BoundingBox(
                0.0,
                Double.NaN,
                10.0,
                10.0
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite and non-negative");
    }

    private CanonicalDocument document(
            String documentId,
            long accessLevel,
            List<CanonicalDocument.Block> blocks
    ) {
        return new CanonicalDocument(
                documentId,
                "v1",
                "Title",
                "source",
                "en",
                KnowledgeDomain.GENERAL,
                accessLevel,
                blocks,
                Map.of()
        );
    }

    private CanonicalDocument.Block block() {
        return new CanonicalDocument.Block(
                "block-1",
                CanonicalDocument.BlockType.PARAGRAPH,
                "Text",
                null,
                1,
                1,
                "Section",
                null
        );
    }
}
