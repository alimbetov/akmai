package kz.alimbetov.akmai.rag.quality;

import java.util.Map;

/**
 * Release-quality gate configuration.
 *
 * <p>Minimum values apply to higher-is-better metrics. Maximum values apply to
 * lower-is-better metrics. Regression budgets are relative to the approved
 * baseline and are evaluated for overall metrics and every comparable slice.</p>
 */
public record RagQualityGateSpec(
        Map<String, Double> minimums,
        Map<String, Double> maximums,
        Map<String, Double> maxDrops,
        Map<String, Double> maxIncreases,
        boolean requireReleaseQualifiedCorpus
) {
    public RagQualityGateSpec {
        minimums = immutableRates("minimums", minimums);
        maximums = immutableRates("maximums", maximums);
        maxDrops = immutableRates("maxDrops", maxDrops);
        maxIncreases = immutableRates("maxIncreases", maxIncreases);
    }

    private static Map<String, Double> immutableRates(
            String name,
            Map<String, Double> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        values.forEach((metric, value) -> {
            if (metric == null || metric.isBlank()) {
                throw new IllegalArgumentException(name + " contains blank metric");
            }
            if (value == null
                    || !Double.isFinite(value)
                    || value < 0.0
                    || value > 1.0) {
                throw new IllegalArgumentException(
                        name + " values must be finite and between 0 and 1"
                );
            }
        });
        return Map.copyOf(values);
    }
}
