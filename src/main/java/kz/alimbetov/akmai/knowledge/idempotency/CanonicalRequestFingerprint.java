package kz.alimbetov.akmai.knowledge.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.model.KnowledgeLanguage;
import org.springframework.stereotype.Component;

@Component
public class CanonicalRequestFingerprint {

    private final ObjectMapper objectMapper;

    public CanonicalRequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
        canonical.put("metadata", canonicalValue(request.metadata()));

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
