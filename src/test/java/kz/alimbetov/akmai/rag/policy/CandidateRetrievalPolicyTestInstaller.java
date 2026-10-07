package kz.alimbetov.akmai.rag.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Installs a retrieval candidate only inside an isolated benchmark Testcontainers
 * database. This deliberately bypasses real promotion evidence so the candidate
 * can be replayed through the production planner before it is eligible for
 * SHADOW/CANARY/APPROVED in an operational environment.
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
                Map.of(
                        "securityPassed", true,
                        "correctnessPassed", true,
                        "qualityPassed", true,
                        "canaryPassed", false,
                        "evaluationOnly", true
                ),
                Map.of(
                        "performancePassed", true,
                        "evaluationOnly", true
                )
        );
        promotionService.makeCanary(
                RagPolicyType.RETRIEVAL,
                candidate.version()
        );
        repository.attachReports(
                RagPolicyType.RETRIEVAL,
                candidate.version(),
                Map.of(
                        "securityPassed", true,
                        "correctnessPassed", true,
                        "qualityPassed", true,
                        "canaryPassed", true,
                        "evaluationOnly", true
                ),
                Map.of(
                        "performancePassed", true,
                        "evaluationOnly", true
                )
        );
        promotionService.approve(
                RagPolicyType.RETRIEVAL,
                candidate.version()
        );
        provider.invalidate();
        return candidate.version();
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
