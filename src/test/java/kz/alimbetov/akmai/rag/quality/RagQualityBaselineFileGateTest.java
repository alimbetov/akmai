package kz.alimbetov.akmai.rag.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "AKMAI_QUALITY_BASELINE_GATE", matches = "true")
class RagQualityBaselineFileGateTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RagQualityBaselineComparator comparator =
            new RagQualityBaselineComparator();

    @Test
    void releaseCandidateMustPassAbsoluteAndBaselineRegressionGates()
            throws Exception {
        Path baselinePath = Path.of(required("AKMAI_QUALITY_BASELINE_FILE"));
        Path candidatePath = Path.of(env(
                "AKMAI_QUALITY_CANDIDATE_FILE",
                "target/quality/rag-benchmark-v1-release.json"
        ));
        assertThat(baselinePath).exists().isRegularFile();
        assertThat(candidatePath).exists().isRegularFile();

        RagQualitySnapshot baseline = objectMapper.readValue(
                baselinePath.toFile(),
                RagQualitySnapshot.class
        );
        RagQualitySnapshot candidate = objectMapper.readValue(
                candidatePath.toFile(),
                RagQualitySnapshot.class
        );
        RagQualityBaselineComparator.Comparison comparison = comparator.compare(
                baseline,
                candidate,
                gateSpec()
        );

        LinkedHashMap<String, Object> report = new LinkedHashMap<>();
        report.put("baselineFile", baselinePath.toString());
        report.put("candidateFile", candidatePath.toString());
        report.put("passed", comparison.passed());
        report.put("failures", comparison.failures());
        report.put("deltas", comparison.deltas());
        report.put(
                "policyQualityReport",
                comparison.toPolicyQualityReport(
                        true,
                        true,
                        false,
                        candidate
                )
        );

        Path output = Path.of("target", "quality", "quality-gate-comparison.json");
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), report);

        assertThat(comparison.failures())
                .as("quality baseline comparison")
                .isEmpty();
    }

    private RagQualityGateSpec gateSpec() {
        return new RagQualityGateSpec(
                Map.of(
                        "recallAt10", number("AKMAI_QUALITY_MIN_RECALL10", 0.90),
                        "mrr", number("AKMAI_QUALITY_MIN_MRR", 0.80),
                        "ndcgAt10", number("AKMAI_QUALITY_MIN_NDCG10", 0.80),
                        "contextRecall", number("AKMAI_QUALITY_MIN_CONTEXT_RECALL", 0.80),
                        "contextPrecision", number("AKMAI_QUALITY_MIN_CONTEXT_PRECISION", 0.70),
                        "abstentionPrecision", number("AKMAI_QUALITY_MIN_ABSTENTION_PRECISION", 0.85),
                        "abstentionRecall", number("AKMAI_QUALITY_MIN_ABSTENTION_RECALL", 0.80)
                ),
                Map.of(
                        "falseAnswerRate", number("AKMAI_QUALITY_MAX_FALSE_ANSWER_RATE", 0.02),
                        "falseAbstentionRate", number("AKMAI_QUALITY_MAX_FALSE_ABSTENTION_RATE", 0.12)
                ),
                Map.of(
                        "recallAt10", number("AKMAI_QUALITY_MAX_RECALL10_DROP", 0.02),
                        "mrr", number("AKMAI_QUALITY_MAX_MRR_DROP", 0.02),
                        "ndcgAt10", number("AKMAI_QUALITY_MAX_NDCG10_DROP", 0.02),
                        "contextRecall", number("AKMAI_QUALITY_MAX_CONTEXT_RECALL_DROP", 0.03),
                        "contextPrecision", number("AKMAI_QUALITY_MAX_CONTEXT_PRECISION_DROP", 0.03),
                        "evidenceDensity", number("AKMAI_QUALITY_MAX_EVIDENCE_DENSITY_DROP", 0.03)
                ),
                Map.of(
                        "falseAnswerRate", number("AKMAI_QUALITY_MAX_FALSE_ANSWER_INCREASE", 0.01),
                        "falseAbstentionRate", number("AKMAI_QUALITY_MAX_FALSE_ABSTENTION_INCREASE", 0.02)
                ),
                true
        );
    }

    private double number(String name, double fallback) {
        String value = System.getenv(name);
        double parsed = value == null || value.isBlank()
                ? fallback
                : Double.parseDouble(value.trim());
        if (!Double.isFinite(parsed) || parsed < 0.0 || parsed > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
        return parsed;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable " + name);
        }
        return value;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
