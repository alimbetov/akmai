package kz.alimbetov.akmai.config;

import kz.alimbetov.akmai.knowledge.lifecycle.RetentionProperties;
import kz.alimbetov.akmai.rag.policy.CanaryEvaluationProperties;
import kz.alimbetov.akmai.rag.policy.RouterLearningProperties;
import kz.alimbetov.akmai.rag.policy.ShadowEvaluationProperties;
import kz.alimbetov.akmai.rag.query.AdvancedRetrievalProperties;
import kz.alimbetov.akmai.rag.retrieval.ConceptRetrievalProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalFusionProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalIntelligenceProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalProperties;
import kz.alimbetov.akmai.runtimeconfig.AppParameterProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    VectorStorageProperties.class,
    RetentionProperties.class,
    RetrievalProperties.class,
    ConceptRetrievalProperties.class,
    RetrievalFusionProperties.class,
    AdaptiveRetrievalProperties.class,
    RetrievalIntelligenceProperties.class,
    AdvancedRetrievalProperties.class,
    RouterLearningProperties.class,
    ShadowEvaluationProperties.class,
    CanaryEvaluationProperties.class,
    ApiProperties.class,
    IdempotencyProperties.class,
    AsyncIngestionProperties.class,
    SecurityProperties.class,
    ReconciliationProperties.class,
    ModelBudgetProperties.class,
    ReembeddingProperties.class,
    RetentionCleanupProperties.class,
    RetentionEconomicsProperties.class,
    AdaptiveGraphProperties.class,
    AdaptiveGraphCompetitionProperties.class,
    SemanticMemoryProperties.class,
    SelfOptimizingRagProperties.class,
    AppParameterProperties.class
})
public class AkmaiConfiguration {
}
