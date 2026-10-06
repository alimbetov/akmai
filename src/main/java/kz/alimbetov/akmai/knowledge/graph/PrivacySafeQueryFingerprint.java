package kz.alimbetov.akmai.knowledge.graph;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Comparator;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.query.QueryOrigin;
import org.springframework.stereotype.Component;

@Component
public class PrivacySafeQueryFingerprint {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AdaptiveGraphProperties properties;

    public PrivacySafeQueryFingerprint(AdaptiveGraphProperties properties) {
        this.properties = properties;
    }

    public int bucket(List<QueryChunk> queryChunks) {
        if (queryChunks == null || queryChunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "queryChunks must not be empty"
            );
        }

        String secret = properties.learning().fingerprintSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "adaptive graph fingerprint secret is not configured"
            );
        }

        List<QueryChunk> originalChunks = queryChunks.stream()
                .filter(java.util.Objects::nonNull)
                .filter(chunk -> chunk.origin() == QueryOrigin.ORIGINAL)
                .toList();
        List<QueryChunk> fingerprintChunks = originalChunks.isEmpty()
                ? queryChunks
                : originalChunks;

        String canonical = fingerprintChunks.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(QueryChunk::index))
                .map(chunk -> chunk.index()
                        + "|"
                        + safe(chunk.language())
                        + "|"
                        + safe(chunk.normalizedText()))
                .reduce(
                        "graph-v" + properties.graphVersion(),
                        (left, right) -> left + "\n" + right
                );

        byte[] digest = hmac(secret, canonical);
        int value = ((digest[0] & 0xff) << 8) | (digest[1] & 0xff);
        return value % AssociationEvidence.QUERY_SUPPORT_BUCKETS;
    }

    private byte[] hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            ));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Cannot calculate adaptive graph query fingerprint",
                    exception
            );
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
