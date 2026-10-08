package kz.alimbetov.akmai.knowledge.graph.dream;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic semantic-policy fingerprint used to isolate Dream observations
 * across embedding/policy changes and rolling deployments.
 */
public final class DreamPolicyFingerprint {

    public static final String CONFIDENCE_FORMULA_V1 =
            "reciprocal-rank-confidence-v1";
    public static final String NORMALIZATION_COSINE_V1 =
            "cosine-similarity-v1";
    public static final String ALGORITHM_V1 =
            "dream-reciprocal-knn-v1";

    private DreamPolicyFingerprint() {
    }

    public static String sha256(PolicyMaterial material) {
        if (material == null) {
            throw new IllegalArgumentException("policy material must not be null");
        }
        String canonical = material.canonicalForm();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record PolicyMaterial(
            String embeddingProfileId,
            int dimensions,
            String distanceSemantics,
            boolean sameLanguageOnly,
            int topK,
            double candidateThreshold,
            double activationThreshold,
            double retentionThreshold,
            double forgettingThreshold,
            String confidenceFormulaVersion,
            String normalizationSemantics,
            String algorithmVersion
    ) {
        public PolicyMaterial {
            requireText("embeddingProfileId", embeddingProfileId);
            if (dimensions <= 0) {
                throw new IllegalArgumentException("dimensions must be positive");
            }
            requireText("distanceSemantics", distanceSemantics);
            if (topK < 2 || topK > 256) {
                throw new IllegalArgumentException("topK must be in [2, 256]");
            }
            bounded("candidateThreshold", candidateThreshold);
            bounded("activationThreshold", activationThreshold);
            bounded("retentionThreshold", retentionThreshold);
            bounded("forgettingThreshold", forgettingThreshold);
            requireText("confidenceFormulaVersion", confidenceFormulaVersion);
            requireText("normalizationSemantics", normalizationSemantics);
            requireText("algorithmVersion", algorithmVersion);
        }

        public String canonicalForm() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("activationThreshold", Double.toString(activationThreshold));
            fields.put("algorithmVersion", algorithmVersion);
            fields.put("candidateThreshold", Double.toString(candidateThreshold));
            fields.put("confidenceFormulaVersion", confidenceFormulaVersion);
            fields.put("dimensions", Integer.toString(dimensions));
            fields.put("distanceSemantics", distanceSemantics);
            fields.put("embeddingProfileId", embeddingProfileId);
            fields.put("forgettingThreshold", Double.toString(forgettingThreshold));
            fields.put("normalizationSemantics", normalizationSemantics);
            fields.put("retentionThreshold", Double.toString(retentionThreshold));
            fields.put("sameLanguageOnly", Boolean.toString(sameLanguageOnly));
            fields.put("topK", Integer.toString(topK));
            StringBuilder canonical = new StringBuilder();
            fields.forEach((key, value) -> canonical
                    .append(key)
                    .append('=')
                    .append(value)
                    .append('\n'));
            return canonical.toString();
        }

        private static void requireText(String name, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }

        private static void bounded(String name, double value) {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException(name + " must be in [0, 1]");
            }
        }
    }
}
