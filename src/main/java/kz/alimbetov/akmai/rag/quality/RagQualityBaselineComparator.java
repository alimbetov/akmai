package kz.alimbetov.akmai.rag.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RagQualityBaselineComparator {

    public Comparison compare(
            RagQualitySnapshot baseline,
            RagQualitySnapshot candidate,
            RagQualityGateSpec spec
    ) {
        if (baseline == null || candidate == null || spec == null) {
            throw new IllegalArgumentException(
                    "baseline, candidate and gate spec are required"
            );
        }

        List<Failure> failures = new ArrayList<>();
        LinkedHashMap<String, Double> deltas = new LinkedHashMap<>();

        if (spec.requireReleaseQualifiedCorpus()
                && !candidate.releaseCorpusQualified()) {
            failures.add(new Failure(
                    "releaseCorpusQualified",
                    "overall",
                    1.0,
                    0.0,
                    "candidate corpus is not release-qualified"
            ));
        }

        evaluateAbsolute(candidate.overall(), "overall", spec, failures);
        evaluateRelative(
                baseline.overall(),
                candidate.overall(),
                "overall",
                spec,
                failures,
                deltas
        );

        compareSlices(
                "language",
                baseline.byLanguage(),
                candidate.byLanguage(),
                spec,
                failures,
                deltas
        );
        compareSlices(
                "domain",
                baseline.byDomain(),
                candidate.byDomain(),
                spec,
                failures,
                deltas
        );
        compareSlices(
                "queryClass",
                baseline.byQueryClass(),
                candidate.byQueryClass(),
                spec,
                failures,
                deltas
        );

        return new Comparison(
                failures.isEmpty(),
                List.copyOf(failures),
                Map.copyOf(deltas)
        );
    }

    private void compareSlices(
            String dimension,
            Map<String, RagQualityMetrics> baseline,
            Map<String, RagQualityMetrics> candidate,
            RagQualityGateSpec spec,
            List<Failure> failures,
            Map<String, Double> deltas
    ) {
        candidate.forEach((slice, candidateMetrics) -> {
            String scope = dimension + ":" + slice;
            evaluateAbsolute(candidateMetrics, scope, spec, failures);
            RagQualityMetrics baselineMetrics = baseline.get(slice);
            if (baselineMetrics != null) {
                evaluateRelative(
                        baselineMetrics,
                        candidateMetrics,
                        scope,
                        spec,
                        failures,
                        deltas
                );
            }
        });
    }

    private void evaluateAbsolute(
            RagQualityMetrics candidate,
            String scope,
            RagQualityGateSpec spec,
            List<Failure> failures
    ) {
        spec.minimums().forEach((metric, minimum) -> {
            double actual = candidate.value(metric);
            if (actual < minimum) {
                failures.add(new Failure(
                        metric,
                        scope,
                        minimum,
                        actual,
                        "below absolute minimum"
                ));
            }
        });
        spec.maximums().forEach((metric, maximum) -> {
            double actual = candidate.value(metric);
            if (actual > maximum) {
                failures.add(new Failure(
                        metric,
                        scope,
                        maximum,
                        actual,
                        "above absolute maximum"
                ));
            }
        });
    }

    private void evaluateRelative(
            RagQualityMetrics baseline,
            RagQualityMetrics candidate,
            String scope,
            RagQualityGateSpec spec,
            List<Failure> failures,
            Map<String, Double> deltas
    ) {
        spec.maxDrops().forEach((metric, budget) -> {
            double delta = candidate.value(metric) - baseline.value(metric);
            deltas.put(scope + "." + metric, delta);
            if (delta < -budget) {
                failures.add(new Failure(
                        metric,
                        scope,
                        -budget,
                        delta,
                        "regressed beyond allowed drop"
                ));
            }
        });
        spec.maxIncreases().forEach((metric, budget) -> {
            double delta = candidate.value(metric) - baseline.value(metric);
            deltas.put(scope + "." + metric, delta);
            if (delta > budget) {
                failures.add(new Failure(
                        metric,
                        scope,
                        budget,
                        delta,
                        "increased beyond allowed budget"
                ));
            }
        });
    }

    public record Comparison(
            boolean passed,
            List<Failure> failures,
            Map<String, Double> deltas
    ) {
        public Comparison {
            failures = failures == null ? List.of() : List.copyOf(failures);
            deltas = deltas == null ? Map.of() : Map.copyOf(deltas);
        }

        public Map<String, Object> toPolicyQualityReport(
                boolean securityPassed,
                boolean correctnessPassed,
                boolean canaryPassed,
                RagQualitySnapshot candidate
        ) {
            LinkedHashMap<String, Object> report = new LinkedHashMap<>();
            report.put("securityPassed", securityPassed);
            report.put("correctnessPassed", correctnessPassed);
            report.put("qualityPassed", passed);
            report.put("canaryPassed", canaryPassed);
            report.put("benchmarkVersion", candidate.benchmarkVersion());
            report.put("corpusVersion", candidate.corpusVersion());
            report.put("gitSha", candidate.gitSha());
            report.put("embeddingProfile", candidate.embeddingProfile());
            report.put("retrievalPolicyVersion", candidate.retrievalPolicyVersion());
            report.put("learningPolicyVersion", candidate.learningPolicyVersion());
            report.put("groundingPolicyVersion", candidate.groundingPolicyVersion());
            report.put("runtimeProfile", candidate.runtimeProfile());
            report.put("caseCount", candidate.caseCount());
            report.put("releaseCorpusQualified", candidate.releaseCorpusQualified());
            report.put("failures", failures);
            report.put("deltas", deltas);
            return Map.copyOf(report);
        }
    }

    public record Failure(
            String metric,
            String scope,
            double expected,
            double actual,
            String reason
    ) {
    }
}
