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
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.api.CanonicalDocument;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;
import org.springframework.stereotype.Component;

@Component
public class CanonicalRequestFingerprint {

    private final ObjectMapper objectMapper;
    private final CanonicalKnowledgeHasher canonicalKnowledgeHasher;

    public CanonicalRequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.canonicalKnowledgeHasher = new CanonicalKnowledgeHasher(objectMapper);
    }

    public String fingerprint(AddKnowledgeRequest request) {
        TreeMap<String, Object> canonical = new TreeMap<>();
        canonical.put("documentId", text(request.documentId()));
        canonical.put("title", text(request.title()));
        canonical.put("text", text(request.text()));
        canonical.put("source", text(request.source()));
        canonical.put(
                "language",
                KnowledgeLanguage.parse(request.language()).code()
        );
        canonical.put("domain", request.domain().name());
        canonical.put("accessLevel", request.accessLevel());
        canonical.put("metadata", canonicalValue(request.metadata()));
        return digest(canonical);
    }

    public String fingerprint(CanonicalDocument document) {
        TreeMap<String, Object> canonical = new TreeMap<>();
        canonical.put("documentId", text(document.documentId()));
        canonical.put("version", text(document.version()));
        canonical.put("title", text(document.title()));
        canonical.put("source", text(document.source()));
        canonical.put(
                "language",
                KnowledgeLanguage.parse(document.language()).code()
        );
        canonical.put("domain", document.domain().name());
        canonical.put("accessLevel", document.accessLevel());
        canonical.put("metadata", canonicalValue(document.metadata()));
        canonical.put(
                "blocks",
                document.blocks().stream()
                        .map(this::canonicalBlock)
                        .toList()
        );
        return digest(canonical);
    }

    /**
     * FileService retries are identified by canonical semantic content. Parser timestamps and
     * physical object-storage locations are deliberately excluded: neither changes the knowledge
     * AkmAI is being asked to publish.
     */
    public String fingerprint(CanonicalKnowledgeDocument document) {
        return canonicalKnowledgeHasher.hash(document);
    }

    public String canonicalHash(CanonicalKnowledgeDocument document) {
        return canonicalKnowledgeHasher.hash(document);
    }

    private Map<String, Object> canonicalBlock(CanonicalDocument.Block block) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("blockId", text(block.blockId()));
        value.put("type", block.type().name());
        value.put("text", text(block.text()));
        value.put("headingLevel", block.headingLevel());
        value.put("pageFrom", block.pageFrom());
        value.put("pageTo", block.pageTo());
        value.put("sectionPath", text(block.sectionPath()));
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
                    "Request cannot be canonicalized",
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
