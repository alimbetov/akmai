package kz.alimbetov.akmai.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import kz.alimbetov.akmai.config.ApiProperties;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import org.junit.jupiter.api.Test;

class ApiRequestValidatorTest {

    private final ApiRequestValidator validator = new ApiRequestValidator(
            new ApiProperties(100, 20, 100, 4, 3, 20, 20),
            new ObjectMapper()
    );

    @Test
    void rejectsOversizedDocumentQuestionAndControlCharacterIdentity() {
        assertThatThrownBy(() -> validator.validateQuestion("x".repeat(21)))
                .isInstanceOf(ApiValidationException.class)
                .hasMessageContaining("maximum length");

        assertThatThrownBy(() -> validator.validateKnowledge(request(
                "doc",
                "x".repeat(101),
                Map.of()
        )))
                .isInstanceOf(ApiValidationException.class)
                .hasMessageContaining("text");

        assertThatThrownBy(() -> validator.validateKnowledge(request(
                "doc\nother",
                "text",
                Map.of()
        )))
                .isInstanceOf(ApiValidationException.class)
                .hasMessageContaining("control characters");
    }

    @Test
    void rejectsReservedNullUnsafeAndOverdeepMetadata() {
        assertThatThrownBy(() -> validator.validateKnowledge(request(
                "doc",
                "text",
                Map.of("akmaiGeneration", 1)
        )))
                .isInstanceOf(ApiValidationException.class)
                .hasMessageContaining("reserved");

        assertThatThrownBy(() -> validator.validateKnowledge(request(
                "doc",
                "text",
                Map.of("authorityTier", 0)
        )))
                .isInstanceOf(ApiValidationException.class)
                .hasMessageContaining("reserved");

        assertThatThrownBy(() -> validator.validateKnowledge(request(
                "doc",
                "text",
                Map.of("bad", new Object())
        )))
                .isInstanceOf(ApiValidationException.class)
                .hasMessageContaining("unsupported value type");

        assertThatThrownBy(() -> validator.validateKnowledge(request(
                "doc",
                "text",
                Map.of("a", Map.of("b", Map.of("c", Map.of("d", 1))))
        )))
                .isInstanceOf(ApiValidationException.class)
                .hasMessageContaining("nesting depth");
    }


    @Test
    void rejectsInvalidOrMetadataDefinedAccessLevel() {
        assertThatThrownBy(() -> new AddKnowledgeRequest(
                "doc",
                "title",
                "text",
                "source",
                "en",
                KnowledgeDomain.GENERAL,
                0L,
                Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessLevel");

        assertThatThrownBy(() -> validator.validateKnowledge(
                new AddKnowledgeRequest(
                        "doc",
                        "title",
                        "text",
                        "source",
                        "en",
                        KnowledgeDomain.GENERAL,
                        1L,
                        Map.of("access_level", 99)
                )
        )).isInstanceOf(ApiValidationException.class)
                .hasMessageContaining("access_level")
                .hasMessageContaining("reserved");
    }

    private AddKnowledgeRequest request(
            String documentId,
            String text,
            Map<String, Object> metadata
    ) {
        return new AddKnowledgeRequest(
                documentId,
                "title",
                text,
                "source",
                "en",
                KnowledgeDomain.GENERAL,
                1L,
                metadata
        );
    }
}
