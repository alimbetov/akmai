package kz.alimbetov.akmai.knowledge.chunking;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "akmai.chunking")
public record ChunkingProperties(
        @Min(1) int targetTokens,
        @Min(1) int softMaxTokens,
        @Min(1) int hardMaxTokens,
        @Min(1) int minTokens
) {
    public ChunkingProperties {
        if (targetTokens <= 0
                || softMaxTokens <= 0
                || hardMaxTokens <= 0
                || minTokens <= 0) {
            throw new IllegalArgumentException(
                    "chunking token limits must be positive"
            );
        }
        if (minTokens > targetTokens
                || targetTokens > softMaxTokens
                || softMaxTokens > hardMaxTokens) {
            throw new IllegalArgumentException(
                    "chunking must satisfy min <= target <= soft <= hard"
            );
        }
    }
}
