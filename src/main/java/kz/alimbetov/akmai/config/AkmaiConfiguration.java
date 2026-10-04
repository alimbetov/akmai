package kz.alimbetov.akmai.config;

import kz.alimbetov.akmai.knowledge.lifecycle.RetentionProperties;
import kz.alimbetov.akmai.rag.retrieval.RetrievalProperties;
import kz.alimbetov.akmai.runtimeconfig.AppParameterProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    VectorStorageProperties.class,
    RetentionProperties.class,
    RetrievalProperties.class,
    ApiProperties.class,
    IdempotencyProperties.class,
    SecurityProperties.class,
    ReconciliationProperties.class,
    ModelBudgetProperties.class,
    ReembeddingProperties.class,
    RetentionCleanupProperties.class,
    RetentionEconomicsProperties.class,
    AdaptiveGraphProperties.class,
    AdaptiveGraphCompetitionProperties.class,
    AppParameterProperties.class
})
public class AkmaiConfiguration {
}
