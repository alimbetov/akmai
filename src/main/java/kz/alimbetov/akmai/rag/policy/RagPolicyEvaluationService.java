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
        RagQualityBaselineComparator.Comparison comparison =
                qualityComparator.compare(baseline, candidate, gateSpec);
        Map<String, Object> qualityReport = comparison.toPolicyQualityReport(
                securityPassed,
                correctnessPassed,
                canaryPassed,
                candidate
        );
        repository.attachReports(
                type,
                version,
                qualityReport,
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
