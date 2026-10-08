package kz.alimbetov.akmai.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.adaptive-graph")
public record AdaptiveGraphProperties(
        boolean learningEnabled,
        boolean maintenanceEnabled,
        boolean shadowExpansionEnabled,
        boolean expansionEnabled,
        boolean dreamEnabled,
        int graphVersion,
        Learning learning,
        ShadowExpansion shadowExpansion,
        Scoring scoring,
        Maintenance maintenance,
        BandQuotas quotas,
        Storage storage,
        Dream dream
) {

    private static final int STORAGE_HASH_BUCKETS_V1 = 32;
    private static final int QUERY_SUPPORT_BUCKETS = 256;

    public AdaptiveGraphProperties(
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
        this(
                learningEnabled,
                maintenanceEnabled,
                shadowExpansionEnabled,
                expansionEnabled,
                false,
                graphVersion,
                learning,
                shadowExpansion,
                scoring,
                maintenance,
                quotas,
                storage,
                Dream.defaults()
        );
    }

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
        if (dream == null) {
            throw new IllegalArgumentException(
                    "adaptive-graph dream must not be null"
            );
        }
        if (dream.applyEnabled() && !dreamEnabled) {
            throw new IllegalArgumentException(
                    "adaptive-graph dream apply requires dream-enabled=true"
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

        private static void boundedInt(String name, int value, int minimum, int maximum) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(
                        "adaptive-graph " + name + " must be in [" + minimum + ", " + maximum + "]"
                );
            }
        }

        private static void boundedUnit(String name, double value) {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException(
                        "adaptive-graph " + name + " must be in [0, 1]"
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
            double demoteHot,
            int minimumDistinctQuerySupportWarm,
            int minimumDistinctQuerySupportHot,
            int minimumCitationCountHot
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
            boundedInt("minimumDistinctQuerySupportWarm", minimumDistinctQuerySupportWarm, 1, QUERY_SUPPORT_BUCKETS);
            boundedInt("minimumDistinctQuerySupportHot", minimumDistinctQuerySupportHot, minimumDistinctQuerySupportWarm, QUERY_SUPPORT_BUCKETS);
            boundedInt("minimumCitationCountHot", minimumCitationCountHot, 1, Integer.MAX_VALUE);
            if (!(demoteWarm < promoteWarm && promoteWarm < demoteHot && demoteHot < promoteHot)) {
                throw new IllegalArgumentException(
                        "adaptive-graph hysteresis must satisfy demoteWarm < promoteWarm < demoteHot < promoteHot"
                );
            }
        }

        public double totalEvidenceWeight() {
            return distinctQueryWeight + contextWeight + citationWeight;
        }

        private static void positiveFinite(String name, double value) {
            if (!Double.isFinite(value) || value <= 0) {
                throw new IllegalArgumentException(
                        "adaptive-graph " + name + " must be finite and positive"
                );
            }
        }

        private static void boundedInt(String name, int value, int minimum, int maximum) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(
                        "adaptive-graph " + name + " must be in [" + minimum + ", " + maximum + "]"
                );
            }
        }

        private static void bounded(String name, double value) {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException(
                        "adaptive-graph " + name + " must be in [0, 1]"
                );
            }
        }

        private static void positiveDuration(String name, Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(
                        "adaptive-graph " + name + " must be positive"
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
            if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()) {
                throw new IllegalArgumentException(
                        "adaptive-graph fixedDelay must be positive"
                );
            }
        }
    }

    public record BandQuotas(int hot, int warm, int candidate) {
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
                        "adaptive-graph " + name + " quota must be in [1, 128]"
                );
            }
        }
    }

    public record Storage(int hashBucketsPerAcl) {
        public Storage {
            if (hashBucketsPerAcl < 1) {
                throw new IllegalArgumentException("hashBucketsPerAcl must be positive");
            }
        }
    }

    public record Dream(
            boolean applyEnabled,
            String cron,
            String zone,
            int topK,
            double candidateThreshold,
            double activationThreshold,
            double retentionThreshold,
            double forgettingThreshold,
            int maxNewEdgesPerChunk,
            int maxSourcesPerRun,
            int rescanSourcesPerRun,
            int batchSize,
            int negativeStreakForForgetting,
            boolean decayEnabled,
            int maxAnnQueriesPerRun,
            int maxReverseAnnQueriesPerRun,
            long maxDbRowsTouchedPerRun,
            Duration maxRunDuration,
            Duration queryTimeout,
            Duration transactionTimeout,
            Duration leaseDuration,
            Duration heartbeatInterval,
            int maxDbConcurrency,
            int maxForwardAnnConcurrency,
            int maxReverseAnnConcurrency,
            int reverseCacheMaximumSize,
            String semanticPolicyVersion
    ) {
        public Dream {
            boundedInt("dream.topK", topK, 2, 256);
            boundedUnit("dream.candidateThreshold", candidateThreshold);
            boundedUnit("dream.activationThreshold", activationThreshold);
            boundedUnit("dream.retentionThreshold", retentionThreshold);
            boundedUnit("dream.forgettingThreshold", forgettingThreshold);
            if (!(candidateThreshold <= forgettingThreshold
                    && forgettingThreshold < retentionThreshold
                    && retentionThreshold < activationThreshold)) {
                throw new IllegalArgumentException(
                        "adaptive-graph Dream thresholds must satisfy candidate <= forgetting < retention < activation"
                );
            }
            boundedInt("dream.maxNewEdgesPerChunk", maxNewEdgesPerChunk, 1, 16);
            positive("dream.maxSourcesPerRun", maxSourcesPerRun);
            if (rescanSourcesPerRun < 0) {
                throw new IllegalArgumentException("adaptive-graph dream.rescanSourcesPerRun must be non-negative");
            }
            boundedInt("dream.batchSize", batchSize, 1, 1000);
            if (negativeStreakForForgetting < 2) {
                throw new IllegalArgumentException("adaptive-graph dream.negativeStreakForForgetting must be >= 2");
            }
            positive("dream.maxAnnQueriesPerRun", maxAnnQueriesPerRun);
            positive("dream.maxReverseAnnQueriesPerRun", maxReverseAnnQueriesPerRun);
            if (maxDbRowsTouchedPerRun <= 0) {
                throw new IllegalArgumentException("adaptive-graph dream.maxDbRowsTouchedPerRun must be positive");
            }
            positiveDuration("dream.maxRunDuration", maxRunDuration);
            positiveDuration("dream.queryTimeout", queryTimeout);
            positiveDuration("dream.transactionTimeout", transactionTimeout);
            positiveDuration("dream.leaseDuration", leaseDuration);
            positiveDuration("dream.heartbeatInterval", heartbeatInterval);
            if (heartbeatInterval.compareTo(leaseDuration.dividedBy(3)) > 0) {
                throw new IllegalArgumentException("adaptive-graph dream heartbeatInterval must be <= leaseDuration / 3");
            }
            if (maxDbConcurrency != 1
                    || maxForwardAnnConcurrency != 1
                    || maxReverseAnnConcurrency != 1) {
                throw new IllegalArgumentException(
                        "adaptive-graph Dream v1 requires DB/forward-ANN/reverse-ANN concurrency = 1"
                );
            }
            if (reverseCacheMaximumSize < topK) {
                throw new IllegalArgumentException("adaptive-graph dream reverseCacheMaximumSize must be >= topK");
            }
            if (semanticPolicyVersion == null || semanticPolicyVersion.isBlank()) {
                throw new IllegalArgumentException("adaptive-graph dream semanticPolicyVersion must not be blank");
            }
            if (cron == null || cron.isBlank() || !CronExpression.isValidExpression(cron)) {
                throw new IllegalArgumentException("adaptive-graph dream cron must be a valid Spring cron expression");
            }
            try {
                ZoneId.of(zone);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("adaptive-graph dream zone must be a valid zone id", ex);
            }
        }

        public static Dream defaults() {
            return new Dream(
                    false,
                    "0 0 3 * * *",
                    "UTC",
                    32,
                    0.86,
                    0.94,
                    0.90,
                    0.86,
                    3,
                    10_000,
                    1_000,
                    200,
                    3,
                    true,
                    100_000,
                    90_000,
                    1_000_000L,
                    Duration.ofHours(2),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(90),
                    Duration.ofSeconds(25),
                    1,
                    1,
                    1,
                    10_000,
                    "dream-v1"
            );
        }

        private static void boundedInt(String name, int value, int minimum, int maximum) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException("adaptive-graph " + name + " must be in [" + minimum + ", " + maximum + "]");
            }
        }

        private static void boundedUnit(String name, double value) {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("adaptive-graph " + name + " must be in [0, 1]");
            }
        }

        private static void positive(String name, int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("adaptive-graph " + name + " must be positive");
            }
        }

        private static void positiveDuration(String name, Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("adaptive-graph " + name + " must be positive");
            }
        }
    }
}
