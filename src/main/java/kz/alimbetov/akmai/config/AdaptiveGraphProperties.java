package kz.alimbetov.akmai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.adaptive-graph")
public record AdaptiveGraphProperties(
        boolean learningEnabled,
        boolean maintenanceEnabled,
        boolean shadowExpansionEnabled,
        boolean expansionEnabled,
        int graphVersion,
        Learning learning,
        BandQuotas quotas,
        Storage storage
) {

    private static final int STORAGE_HASH_BUCKETS_V1 = 32;

    @ConstructorBinding
    public AdaptiveGraphProperties {
        if (graphVersion <= 0) {
            throw new IllegalArgumentException(
                    "adaptive-graph graphVersion must be positive"
            );
        }
        if (learning == null) {
            throw new IllegalArgumentException(
                    "adaptive-graph learning must not be null"
            );
        }
        if (learningEnabled
                && (learning.fingerprintSecret() == null
                || learning.fingerprintSecret().length() < 32)) {
            throw new IllegalArgumentException(
                    "adaptive-graph learning requires a fingerprint secret "
                            + "of at least 32 characters"
            );
        }
        if (quotas == null) {
            throw new IllegalArgumentException(
                    "adaptive-graph quotas must not be null"
            );
        }
        if (storage == null) {
            throw new IllegalArgumentException(
                    "adaptive-graph storage must not be null"
            );
        }
        if (quotas.total() > 256) {
            throw new IllegalArgumentException(
                    "adaptive-graph total neighbor quota must be <= 256"
            );
        }
        if (storage.hashBucketsPerAcl() != STORAGE_HASH_BUCKETS_V1) {
            throw new IllegalArgumentException(
                    "adaptive-graph schema v1 requires exactly "
                            + STORAGE_HASH_BUCKETS_V1
                            + " hash buckets per ACL"
            );
        }
    }

    public record Learning(
            int maxContextChunks,
            int maxPairsPerRequest,
            String fingerprintSecret
    ) {
        public Learning {
            if (maxContextChunks < 2 || maxContextChunks > 32) {
                throw new IllegalArgumentException(
                        "adaptive-graph maxContextChunks must be in [2, 32]"
                );
            }
            if (maxPairsPerRequest < 1 || maxPairsPerRequest > 128) {
                throw new IllegalArgumentException(
                        "adaptive-graph maxPairsPerRequest must be in [1, 128]"
                );
            }
            fingerprintSecret =
                    fingerprintSecret == null ? "" : fingerprintSecret;
        }

        @Override
        public String toString() {
            return "Learning[maxContextChunks="
                    + maxContextChunks
                    + ", maxPairsPerRequest="
                    + maxPairsPerRequest
                    + ", fingerprintSecret=<redacted>]";
        }
    }

    public record BandQuotas(
            int hot,
            int warm,
            int candidate
    ) {
        public BandQuotas {
            validate("hot", hot);
            validate("warm", warm);
            validate("candidate", candidate);
        }

        public int total() {
            return hot + warm + candidate;
        }

        private static void validate(String name, int value) {
            if (value < 1 || value > 128) {
                throw new IllegalArgumentException(
                        "adaptive-graph "
                                + name
                                + " quota must be in [1, 128]"
                );
            }
        }
    }

    public record Storage(int hashBucketsPerAcl) {
        public Storage {
            if (hashBucketsPerAcl < 1) {
                throw new IllegalArgumentException(
                        "hashBucketsPerAcl must be positive"
                );
            }
        }
    }
}
