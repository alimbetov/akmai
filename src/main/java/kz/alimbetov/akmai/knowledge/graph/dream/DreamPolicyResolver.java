package kz.alimbetov.akmai.knowledge.graph.dream;

import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.config.SemanticMemoryProperties;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfile;
import kz.alimbetov.akmai.knowledge.embedding.EmbeddingProfileService;
import org.springframework.stereotype.Component;

/** Captures one immutable semantic-policy epoch for a Dream run. */
@Component
public class DreamPolicyResolver {

    private final AdaptiveGraphProperties graphProperties;
    private final SemanticMemoryProperties semanticMemoryProperties;
    private final EmbeddingProfileService profileService;

    public DreamPolicyResolver(
            AdaptiveGraphProperties graphProperties,
            SemanticMemoryProperties semanticMemoryProperties,
            EmbeddingProfileService profileService
    ) {
        this.graphProperties = graphProperties;
        this.semanticMemoryProperties = semanticMemoryProperties;
        this.profileService = profileService;
    }

    public ResolvedDreamPolicy resolve() {
        profileService.assertConfiguredProfileIsActive();
        EmbeddingProfile profile = profileService.activeProfile();
        AdaptiveGraphProperties.Dream dream = graphProperties.dream();
        DreamPolicyFingerprint.PolicyMaterial material =
                new DreamPolicyFingerprint.PolicyMaterial(
                        profile.profileId(),
                        profile.dimensions(),
                        profile.distanceType(),
                        semanticMemoryProperties.isSameLanguageOnly(),
                        dream.topK(),
                        dream.candidateThreshold(),
                        dream.activationThreshold(),
                        dream.retentionThreshold(),
                        dream.forgettingThreshold(),
                        DreamPolicyFingerprint.CONFIDENCE_FORMULA_V1,
                        DreamPolicyFingerprint.NORMALIZATION_COSINE_V1,
                        DreamPolicyFingerprint.ALGORITHM_V1
                );
        return new ResolvedDreamPolicy(
                graphProperties.graphVersion(),
                dream.semanticPolicyVersion(),
                DreamPolicyFingerprint.sha256(material),
                profile,
                semanticMemoryProperties.isSameLanguageOnly(),
                dream
        );
    }

    public record ResolvedDreamPolicy(
            int graphVersion,
            String semanticPolicyVersion,
            String fingerprint,
            EmbeddingProfile embeddingProfile,
            boolean sameLanguageOnly,
            AdaptiveGraphProperties.Dream dream
    ) {
    }
}
