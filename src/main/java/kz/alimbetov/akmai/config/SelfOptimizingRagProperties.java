package kz.alimbetov.akmai.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
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
        @DefaultValue("4") @Min(1) @Max(16) int persistentMemoryObservationsPerCluster,
        @DefaultValue("5s") @NotNull Duration persistentMemoryRefreshInterval,
        @DefaultValue("30d") @NotNull Duration persistentMemoryTtl
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
        validateDuration(
                "persistent-memory-refresh-interval",
                persistentMemoryRefreshInterval,
                Duration.ofMillis(100),
                Duration.ofMinutes(5)
        );
        validateDuration(
                "persistent-memory-ttl",
                persistentMemoryTtl,
                Duration.ofMinutes(1),
                Duration.ofDays(365)
        );
        if (persistentMemoryRefreshInterval.compareTo(persistentMemoryTtl) >= 0) {
            throw new IllegalArgumentException(
                    "persistent-memory-refresh-interval must be < persistent-memory-ttl"
            );
        }
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

    private static void validateDuration(
            String name,
            Duration value,
            Duration minimum,
            Duration maximum
    ) {
        if (value == null
                || value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "self-optimizing " + name + " must be between "
                            + minimum + " and " + maximum
            );
        }
    }
}
