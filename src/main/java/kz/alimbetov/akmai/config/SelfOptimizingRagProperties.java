package kz.alimbetov.akmai.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.self-optimizing")
public record SelfOptimizingRagProperties(
        @DefaultValue("false") boolean learningEventsEnabled,
        @DefaultValue("false") boolean persistentQueryMemoryEnabled,
        @DefaultValue("false") boolean semanticGroundingEnabled,
        @DefaultValue("") String fingerprintSecret,
        @DefaultValue("runtime-v1") @NotBlank String corpusVersion,
        @DefaultValue("retrieval-v1") @NotBlank String retrievalPolicyVersion,
        @DefaultValue("learning-v1") @NotBlank String learningPolicyVersion,
        @DefaultValue("grounding-v1") @NotBlank String groundingPolicyVersion,
        @DefaultValue("2048") @Min(32) @Max(10000) int persistentMemoryMaxEntries,
        @DefaultValue("4") @Min(1) @Max(16) int persistentMemoryObservationsPerCluster
) {
    public SelfOptimizingRagProperties {
        fingerprintSecret = fingerprintSecret == null
                ? ""
                : fingerprintSecret.trim();
        corpusVersion = normalizeVersion("corpus-version", corpusVersion);
        retrievalPolicyVersion = normalizeVersion(
                "retrieval-policy-version",
                retrievalPolicyVersion
        );
        learningPolicyVersion = normalizeVersion(
                "learning-policy-version",
                learningPolicyVersion
        );
        groundingPolicyVersion = normalizeVersion(
                "grounding-policy-version",
                groundingPolicyVersion
        );
        if ((learningEventsEnabled || persistentQueryMemoryEnabled)
                && fingerprintSecret.length() < 32) {
            throw new IllegalArgumentException(
                    "self-optimizing learning requires fingerprint-secret "
                            + "of at least 32 characters"
            );
        }
    }

    public boolean persistentLearningEnabled() {
        return learningEventsEnabled || persistentQueryMemoryEnabled;
    }

    private static String normalizeVersion(String name, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 128) {
            throw new IllegalArgumentException(
                    "self-optimizing " + name + " must be 1..128 characters"
            );
        }
        return normalized;
    }
}
