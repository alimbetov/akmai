package kz.alimbetov.akmai.rag.learning;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import kz.alimbetov.akmai.config.SelfOptimizingRagProperties;
import org.springframework.stereotype.Component;

@Component
public class LearningPrivacyFingerprint {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SelfOptimizingRagProperties properties;

    public LearningPrivacyFingerprint(SelfOptimizingRagProperties properties) {
        this.properties = properties;
    }

    public String fingerprint(String value) {
        if (!properties.persistentLearningEnabled()) {
            return "";
        }
        return fingerprintOperational(value);
    }

    /**
     * Creates a privacy-preserving operational fingerprint when a secret is
     * configured, regardless of whether persistent learning is enabled. This is
     * used by bounded rollout evidence such as CANARY source-diversity checks.
     * Raw identity/query values are never returned or persisted.
     */
    public String fingerprintOperational(String value) {
        String secret = properties.fingerprintSecret();
        if (secret == null || secret.length() < 32) {
            return "";
        }
        String canonical = value == null
                ? ""
                : value.trim().replaceAll("\\s+", " ");
        if (canonical.isBlank()) {
            return "";
        }
        return HexFormat.of().formatHex(hmac(secret, canonical));
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
                    "Cannot calculate learning privacy fingerprint",
                    exception
            );
        }
    }
}
