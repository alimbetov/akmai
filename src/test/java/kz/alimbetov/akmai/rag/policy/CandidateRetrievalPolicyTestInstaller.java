package kz.alimbetov.akmai.rag.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Installs a retrieval candidate only inside an isolated benchmark Testcontainers
 * database. This deliberately uses evaluation-only evidence so the candidate
 * can be replayed through the production planner without changing an operational
 * policy registry.
 */
@Component
public class CandidateRetrievalPolicyTestInstaller {

    private final RagPolicyRegistryRepository repository;
    private final RagPolicyPromotionService promotionService;
    private final ApprovedRetrievalPolicyProvider provider;
    private final ObjectMapper objectMapper;

    public CandidateRetrievalPolicyTestInstaller(
            RagPolicyRegistryRepository repository,
            RagPolicyPromotionService promotionService,
            ApprovedRetrievalPolicyProvider provider,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.promotionService = promotionService;
        this.provider = provider;
        this.objectMapper = objectMapper;
    }

    public String installIfConfigured() throws Exception {
        String file = System.getenv("AKMAI_CANDIDATE_RETRIEVAL_POLICY_FILE");
        if (file == null || file.isBlank()) {
            return "baseline";
        }
        CandidateFile candidate = objectMapper.readValue(
                Path.of(file.trim()).toFile(),
                CandidateFile.class
        );
        if (candidate.version() == null
                || candidate.version().isBlank()
                || candidate.configuration() == null
                || candidate.configuration().isEmpty()) {
            throw new IllegalArgumentException(
                    "candidate retrieval policy requires version and configuration"
            );
        }

        repository.registerCandidate(
                RagPolicyType.RETRIEVAL,
                candidate.version(),
                candidate.configuration()
        );
        repository.attachReports(
                RagPolicyType.RETRIEVAL,
                candidate.version(),
                quality(false, false),
                performance()
        );
        promotionService.makeShadow(
                RagPolicyType.RETRIEVAL,
                candidate.version()
        );

        repository.attachReports(
                RagPolicyType.RETRIEVAL,
                candidate.version(),
                quality(true, false),
                performance()
        );
        promotionService.makeCanary(
                RagPolicyType.RETRIEVAL,
                candidate.version()
        );

        repository.attachReports(
                RagPolicyType.RETRIEVAL,
                candidate.version(),
                quality(true, true),
                performance()
        );
        promotionService.approve(
                RagPolicyType.RETRIEVAL,
                candidate.version()
        );
        provider.invalidate();
        return candidate.version();
    }

    private Map<String, Object> quality(
            boolean shadowPassed,
            boolean canaryPassed
    ) {
        return Map.of(
                "securityPassed", true,
                "correctnessPassed", true,
                "qualityPassed", true,
                "shadowPassed", shadowPassed,
                "canaryPassed", canaryPassed,
                "evaluationOnly", true
        );
    }

    private Map<String, Object> performance() {
        return Map.of(
                "performancePassed", true,
                "evaluationOnly", true
        );
    }

    public record CandidateFile(
            String version,
            Map<String, Object> configuration
    ) {
        public CandidateFile {
            configuration = configuration == null
                    ? Map.of()
                    : Map.copyOf(configuration);
        }
    }
}
