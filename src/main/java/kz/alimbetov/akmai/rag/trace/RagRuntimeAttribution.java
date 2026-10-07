package kz.alimbetov.akmai.rag.trace;

import java.util.LinkedHashMap;
import java.util.Map;
import kz.alimbetov.akmai.config.SelfOptimizingRagProperties;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import kz.alimbetov.akmai.rag.policy.ApprovedRetrievalPolicyProvider;
import org.springframework.stereotype.Component;

@Component
public class RagRuntimeAttribution {

    private final SelfOptimizingRagProperties properties;
    private final EmbeddingProfileService embeddingProfileService;
    private final ApprovedRetrievalPolicyProvider approvedRetrievalPolicyProvider;

    public RagRuntimeAttribution(
            SelfOptimizingRagProperties properties,
            EmbeddingProfileService embeddingProfileService,
            ApprovedRetrievalPolicyProvider approvedRetrievalPolicyProvider
    ) {
        this.properties = properties;
        this.embeddingProfileService = embeddingProfileService;
        this.approvedRetrievalPolicyProvider = approvedRetrievalPolicyProvider;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                env("GITHUB_SHA", system("akmai.git.sha", "local")),
                properties.corpusVersion(),
                activeEmbeddingProfile(),
                approvedRetrievalPolicyProvider.approvedVersion()
                        .orElse(properties.retrievalPolicyVersion()),
                properties.learningPolicyVersion(),
                properties.groundingPolicyVersion(),
                env("AKMAI_RUNTIME_PROFILE", system("akmai.runtime.profile", "default"))
        );
    }

    private String activeEmbeddingProfile() {
        try {
            return embeddingProfileService.activeProfile().profileId();
        } catch (RuntimeException exception) {
            return "unavailable";
        }
    }

    private String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String system(String name, String fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record Snapshot(
            String gitSha,
            String corpusVersion,
            String embeddingProfileId,
            String retrievalPolicyVersion,
            String learningPolicyVersion,
            String groundingPolicyVersion,
            String runtimeProfile
    ) {
        public Map<String, String> asMap() {
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            result.put("gitSha", gitSha);
            result.put("corpusVersion", corpusVersion);
            result.put("embeddingProfileId", embeddingProfileId);
            result.put("retrievalPolicyVersion", retrievalPolicyVersion);
            result.put("learningPolicyVersion", learningPolicyVersion);
            result.put("groundingPolicyVersion", groundingPolicyVersion);
            result.put("runtimeProfile", runtimeProfile);
            return Map.copyOf(result);
        }
    }
}
