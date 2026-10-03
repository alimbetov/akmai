package kz.alimbetov.akmai.knowledge.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import kz.alimbetov.akmai.config.VectorStorageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingProfileResolver {

    private final VectorStorageProperties vectorProperties;
    private final String model;

    public EmbeddingProfileResolver(
            VectorStorageProperties vectorProperties,
            @Value("${spring.ai.ollama.embedding.model:qwen3-embedding:0.6b}")
            String model
    ) {
        this.vectorProperties = vectorProperties;
        this.model = model;
    }

    public EmbeddingProfile configuredProfile() {
        String canonical = String.join(
                "|",
                "ollama",
                model,
                Integer.toString(vectorProperties.dimensions()),
                vectorProperties.distanceType().toUpperCase(),
                vectorProperties.indexType().toUpperCase(),
                vectorProperties.tokenizerProfile()
        );
        String fingerprint = sha256(canonical);
        String profileId = "ep_" + fingerprint;
        String table = "p_" + fingerprint.substring(0, 30);
        return new EmbeddingProfile(
                profileId,
                "ollama",
                model,
                vectorProperties.dimensions(),
                vectorProperties.distanceType().toUpperCase(),
                vectorProperties.tokenizerProfile(),
                fingerprint,
                "akmai_vector",
                table,
                vectorProperties.indexType().toUpperCase(),
                (short) 2,
                Instant.now()
        );
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
