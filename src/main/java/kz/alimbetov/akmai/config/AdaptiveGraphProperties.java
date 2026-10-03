package kz.alimbetov.akmai.config;

import java.time.Duration;
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
        ShadowExpansion shadowExpansion,
        Scoring scoring,
        Maintenance maintenance,
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
        if (shadowExpansion == null) {
            throw new IllegalArgumentException(
                    "adaptive-graph shadowExpansion must not be null"
            );
        }
        if (scoring == null) {
            throw new IllegalArgumentException(
                    "adaptive-graph scoring must not be null"
            );
        }
        if (maintenance == null) {
            throw new IllegalArgumentException(
                    "adaptive-graph maintenance must not be null"
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

    public record ShadowExpansion(
            int maxSeeds,
            int hotPerSeed,
            int warmPerSeed,
            int maxCandidates,
            double minSeedStrength,
            double minHotWeight,
            double minWarmWeight,
            double hotBandFactor,
            double warmBandFactor
    ) {
        public ShadowExpansion {
            boundedInt("maxSeeds", maxSeeds, 1, 32);
            boundedInt("hotPerSeed", hotPerSeed, 1, 64);
            boundedInt("warmPerSeed", warmPerSeed, 0, 64);
            boundedInt("maxCandidates", maxCandidates, 1, 128);
            boundedUnit("minSeedStrength", minSeedStrength);
            boundedUnit("minHotWeight", minHotWeight);
            boundedUnit("minWarmWeight", minWarmWeight);
            boundedUnit("hotBandFactor", hotBandFactor);
            boundedUnit("warmBandFactor", warmBandFactor);
            if (warmBandFactor > hotBandFactor) {
                throw new IllegalArgumentException(
                        "adaptive-graph warmBandFactor must be <= hotBandFactor"
                );
            }
        }

        private static void boundedInt(
                String name,
                int value,
                int minimum,
                int maximum
        ) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(
                        "adaptive-graph "
                                + name
                                + " must be in ["
                                + minimum
                                + ", "
                                + maximum
                                + "]"
                );
            }
        }

        private static void boundedUnit(String name, double value) {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException(
                        "adaptive-graph "
                                + name
                                + " must be in [0, 1]"
                );
            }
        }
    }

    public record Scoring(
            double distinctQueryWeight,
            double contextWeight,
            double citationWeight,
            double distinctQueryScale,
            double contextScale,
            double citationScale,
            Duration halfLife,
            Duration rescoreInterval,
            Duration candidateTtl,
            Duration decayedTtl,
            double promoteWarm,
            double demoteWarm,
            double promoteHot,
            double demoteHot
    ) {
        public Scoring {
            positiveFinite("distinctQueryWeight", distinctQueryWeight);
            positiveFinite("contextWeight", contextWeight);
            positiveFinite("citationWeight", citationWeight);
            positiveFinite("distinctQueryScale", distinctQueryScale);
            positiveFinite("contextScale", contextScale);
            positiveFinite("citationScale", citationScale);
            positiveDuration("halfLife", halfLife);
            positiveDuration("rescoreInterval", rescoreInterval);
            positiveDuration("candidateTtl", candidateTtl);
            positiveDuration("decayedTtl", decayedTtl);

            bounded("demoteWarm", demoteWarm);
            bounded("promoteWarm", promoteWarm);
            bounded("demoteHot", demoteHot);
            bounded("promoteHot", promoteHot);

            if (!(demoteWarm < promoteWarm
                    && promoteWarm < demoteHot
                    && demoteHot < promoteHot)) {
                throw new IllegalArgumentException(
                        "adaptive-graph hysteresis must satisfy "
                                + "demoteWarm < promoteWarm < "
                                + "demoteHot < promoteHot"
                );
            }
        }

        public double totalEvidenceWeight() {
            return distinctQueryWeight
                    + contextWeight
                    + citationWeight;
        }

        private static void positiveFinite(
                String name,
                double value
        ) {
            if (!Double.isFinite(value) || value <= 0) {
                throw new IllegalArgumentException(
                        "adaptive-graph "
                                + name
                                + " must be finite and positive"
                );
            }
        }

        private static void bounded(String name, double value) {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException(
                        "adaptive-graph "
                                + name
                                + " must be in [0, 1]"
                );
            }
        }

        private static void positiveDuration(
                String name,
                Duration value
        ) {
            if (value == null
                    || value.isZero()
                    || value.isNegative()) {
                throw new IllegalArgumentException(
                        "adaptive-graph "
                                + name
                                + " must be positive"
                );
            }
        }
    }

    public record Maintenance(
            int batchSize,
            int maxBatchesPerRun,
            Duration fixedDelay
    ) {
        public Maintenance {
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalArgumentException(
                        "adaptive-graph batchSize must be in [1, 1000]"
                );
            }
            if (maxBatchesPerRun < 1 || maxBatchesPerRun > 100) {
                throw new IllegalArgumentException(
                        "adaptive-graph maxBatchesPerRun must be in [1, 100]"
                );
            }
            if (fixedDelay == null
                    || fixedDelay.isZero()
                    || fixedDelay.isNegative()) {
                throw new IllegalArgumentException(
                        "adaptive-graph fixedDelay must be positive"
                );
            }
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

        public int forBand(kz.alimbetov.akmai.knowledge.graph.AssociationBand band) {
            return switch (band) {
                case HOT -> hot;
                case WARM -> warm;
                case CANDIDATE -> candidate;
                case DECAYED -> 0;
            };
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
