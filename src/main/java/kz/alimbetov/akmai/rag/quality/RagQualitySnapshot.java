package kz.alimbetov.akmai.rag.quality;

import java.time.Instant;
import java.util.Map;

public record RagQualitySnapshot(
        String benchmarkVersion,
        String corpusVersion,
        String gitSha,
        String embeddingProfile,
        String retrievalPolicyVersion,
        String learningPolicyVersion,
        String groundingPolicyVersion,
        String runtimeProfile,
        int caseCount,
        boolean releaseCorpusQualified,
        RagQualityMetrics overall,
        Map<String, RagQualityMetrics> byLanguage,
        Map<String, RagQualityMetrics> byDomain,
        Map<String, RagQualityMetrics> byQueryClass,
        Instant generatedAt
) {
    public RagQualitySnapshot {
        requireText("benchmarkVersion", benchmarkVersion);
        requireText("corpusVersion", corpusVersion);
        requireText("gitSha", gitSha);
        requireText("embeddingProfile", embeddingProfile);
        requireText("retrievalPolicyVersion", retrievalPolicyVersion);
        requireText("learningPolicyVersion", learningPolicyVersion);
        requireText("groundingPolicyVersion", groundingPolicyVersion);
        requireText("runtimeProfile", runtimeProfile);
        if (caseCount <= 0) {
            throw new IllegalArgumentException("caseCount must be positive");
        }
        if (overall == null) {
            throw new IllegalArgumentException("overall metrics are required");
        }
        byLanguage = byLanguage == null ? Map.of() : Map.copyOf(byLanguage);
        byDomain = byDomain == null ? Map.of() : Map.copyOf(byDomain);
        byQueryClass = byQueryClass == null ? Map.of() : Map.copyOf(byQueryClass);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
