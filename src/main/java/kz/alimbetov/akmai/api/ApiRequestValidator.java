package kz.alimbetov.akmai.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kz.alimbetov.akmai.config.ApiProperties;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.model.DocumentMetadata;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;
import org.springframework.stereotype.Component;

@Component
public class ApiRequestValidator {

    private final ApiProperties properties;
    private final ObjectMapper objectMapper;

    public ApiRequestValidator(
            ApiProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void validateKnowledge(AddKnowledgeRequest request) {
        boundedIdentifier("documentId", request.documentId(), 100);
        boundedText("title", request.title(), properties.maxTitleChars());
        boundedText("source", request.source(), properties.maxSourceChars());
        boundedText("text", request.text(), properties.maxDocumentChars());

        try {
            KnowledgeLanguage.parse(request.language());
        } catch (IllegalArgumentException exception) {
            throw new ApiValidationException(exception.getMessage());
        }

        if (request.accessLevel() == null || request.accessLevel() <= 0) {
            throw new ApiValidationException("accessLevel must be positive");
        }

        validateMetadata(request.metadata());
    }

    public void validateQuestion(String question) {
        boundedText("question", question, properties.maxQuestionChars());
    }

    public void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        boundedIdentifier("Idempotency-Key", key, 200);
    }

    private void boundedIdentifier(
            String name,
            String value,
            int maximum
    ) {
        boundedText(name, value, maximum);
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiValidationException(
                    name + " must not contain control characters"
            );
        }
    }

    private void boundedText(String name, String value, int maximum) {
        if (value == null || value.isBlank()) {
            throw new ApiValidationException(name + " must not be blank");
        }
        if (value.length() > maximum) {
            throw new ApiValidationException(
                    name + " exceeds maximum length " + maximum
            );
        }
    }

    private void validateMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }

        MetadataStats stats = inspect(metadata, 1);
        if (stats.entries() > properties.maxMetadataEntries()) {
            throw new ApiValidationException(
                    "metadata exceeds maximum entry count"
            );
        }
        if (stats.depth() > properties.maxMetadataDepth()) {
            throw new ApiValidationException(
                    "metadata exceeds maximum nesting depth"
            );
        }

        try {
            int bytes = objectMapper.writeValueAsString(metadata)
                    .getBytes(StandardCharsets.UTF_8)
                    .length;
            if (bytes > properties.maxMetadataBytes()) {
                throw new ApiValidationException(
                        "metadata exceeds maximum serialized bytes"
                );
            }
        } catch (JsonProcessingException exception) {
            throw new ApiValidationException("metadata is not serializable JSON");
        }
    }

    private MetadataStats inspect(Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            int entries = map.size();
            int maxDepth = depth;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String normalizedKey = key.toLowerCase(Locale.ROOT);
                if (normalizedKey.startsWith("akmai")) {
                    throw new ApiValidationException(
                            "metadata keys beginning with 'akmai' are reserved"
                    );
                }
                if (DocumentMetadata.ACCESS_LEVEL.equals(normalizedKey)) {
                    throw new ApiValidationException(
                            "metadata key '" + DocumentMetadata.ACCESS_LEVEL
                                    + "' is reserved; use accessLevel"
                    );
                }
                MetadataStats nested = inspect(entry.getValue(), depth + 1);
                entries += nested.entries();
                maxDepth = Math.max(maxDepth, nested.depth());
            }
            return new MetadataStats(entries, maxDepth);
        }
        if (value instanceof List<?> list) {
            int entries = list.size();
            int maxDepth = depth;
            for (Object item : list) {
                MetadataStats nested = inspect(item, depth + 1);
                entries += nested.entries();
                maxDepth = Math.max(maxDepth, nested.depth());
            }
            return new MetadataStats(entries, maxDepth);
        }
        if (value != null
                && !(value instanceof String)
                && !(value instanceof Number)
                && !(value instanceof Boolean)) {
            throw new ApiValidationException(
                    "metadata contains unsupported value type"
            );
        }
        return new MetadataStats(0, depth);
    }

    private record MetadataStats(int entries, int depth) {
    }
}
