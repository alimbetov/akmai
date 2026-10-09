package kz.alimbetov.akmai.knowledge.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;
import org.springframework.stereotype.Component;

/** Calculates structured-content identity independent from transport timestamps/storage URLs. */
@Component
public class CanonicalKnowledgeHasher {

    private final ObjectMapper objectMapper;

    public CanonicalKnowledgeHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(CanonicalKnowledgeDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("canonical document is required");
        }
        TreeMap<String, Object> canonical = new TreeMap<>();
        canonical.put("schemaVersion", document.schemaVersion());
        canonical.put("documentId", text(document.documentId()));
        canonical.put("version", text(document.version()));
        canonical.put("title", text(document.title()));
        canonical.put(
                "language",
                KnowledgeLanguage.parse(document.language()).code()
        );
        canonical.put("domain", document.domain().name());
        canonical.put("accessLevel", document.accessLevel());
        canonical.put("source", canonicalSource(document.source()));
        canonical.put("metadata", canonicalValue(document.metadata()));
        canonical.put(
                "blocks",
                document.blocks().stream().map(this::canonicalBlock).toList()
        );
        return digest(canonical);
    }

    private Map<String, Object> canonicalSource(
            CanonicalKnowledgeDocument.Source source
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("type", source.type().name());
        value.put("fileId", text(source.fileId()));
        value.put("sourceVersion", text(source.sourceVersion()));
        value.put("fileName", text(source.fileName()));
        value.put("mediaType", text(source.mediaType()));
        value.put("contentHash", text(source.contentHash()));
        return value;
    }

    private Map<String, Object> canonicalBlock(
            CanonicalKnowledgeDocument.Block block
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("blockId", text(block.blockId()));
        value.put("type", block.type().name());
        value.put("text", text(block.text()));
        value.put("headingLevel", block.headingLevel());
        value.put("pageFrom", block.pageFrom());
        value.put("pageTo", block.pageTo());
        value.put("sectionPath", canonicalValue(block.sectionPath()));
        if (block.boundingBox() != null) {
            value.put("boundingBox", Map.of(
                    "x", block.boundingBox().x(),
                    "y", block.boundingBox().y(),
                    "width", block.boundingBox().width(),
                    "height", block.boundingBox().height()
            ));
        }
        return value;
    }

    private String digest(Map<String, Object> canonical) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(json)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Canonical document cannot be serialized deterministically",
                    exception
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Object canonicalValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return text(string);
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) ->
                    sorted.put(String.valueOf(key), canonicalValue(nested))
            );
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(canonicalValue(item)));
            return result;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString())
                    .stripTrailingZeros()
                    .toPlainString();
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return text(String.valueOf(value));
    }

    private String text(String value) {
        return Normalizer.normalize(
                value == null ? "" : value.trim(),
                Normalizer.Form.NFC
        );
    }
}
