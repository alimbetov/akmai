package kz.alimbetov.akmai.rag.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import kz.alimbetov.akmai.rag.quality.RagQualityBaselineComparator;
import kz.alimbetov.akmai.rag.quality.RagQualityGateSpec;
import kz.alimbetov.akmai.rag.quality.RagQualitySnapshot;
import org.springframework.stereotype.Service;

@Service
public class RagPolicyEvaluationService {

    private final RagPolicyRegistryRepository repository;
    private final RagQualityBaselineComparator qualityComparator;

    public RagPolicyEvaluationService(
            RagPolicyRegistryRepository repository,
            RagQualityBaselineComparator qualityComparator
    ) {
        this.repository = repository;
        this.qualityComparator = qualityComparator;
    }

    public RagQualityBaselineComparator.Comparison attachEvaluation(
            RagPolicyType type,
            String version,
            RagQualitySnapshot baseline,
            RagQualitySnapshot candidate,
            RagQualityGateSpec gateSpec,
            boolean securityPassed,
            boolean correctnessPassed,
            boolean canaryPassed,
            Map<String, Object> performanceReport
    ) {
        return attachEvaluation(
                type,
                version,
                baseline,
                candidate,
                gateSpec,
                securityPassed,
                correctnessPassed,
                false,
                canaryPassed,
                performanceReport
        );
    }

    public RagQualityBaselineComparator.Comparison attachEvaluation(
            RagPolicyType type,
            String version,
            RagQualitySnapshot baseline,
            RagQualitySnapshot candidate,
            RagQualityGateSpec gateSpec,
            boolean securityPassed,
            boolean correctnessPassed,
            boolean shadowPassed,
            boolean canaryPassed,
            Map<String, Object> performanceReport
    ) {
        RagQualityBaselineComparator.Comparison comparison =
                qualityComparator.compare(baseline, candidate, gateSpec);
        LinkedHashMap<String, Object> qualityReport = new LinkedHashMap<>(
                comparison.toPolicyQualityReport(
                        securityPassed,
                        correctnessPassed,
                        canaryPassed,
                        candidate
                )
        );
        qualityReport.put("shadowPassed", shadowPassed);
        repository.attachReports(
                type,
                version,
                Map.copyOf(qualityReport),
                normalizePerformanceReport(performanceReport)
        );
        return comparison;
    }

    private Map<String, Object> normalizePerformanceReport(
            Map<String, Object> performanceReport
    ) {
        if (performanceReport == null || performanceReport.isEmpty()) {
            return Map.of("performancePassed", false);
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>(
                performanceReport
        );
        normalized.putIfAbsent("performancePassed", false);
        return Map.copyOf(normalized);
    }
}
