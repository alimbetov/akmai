package kz.alimbetov.akmai.knowledge.chunking;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "akmai.chunking")
public record ChunkingProperties(
        int targetTokens,
        int softMaxTokens,
        int hardMaxTokens,
        int minTokens
) {
}
