package kz.alimbetov.akmai.rag.retrieval.plan;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.retrieval.adaptive-planner")
public record AdaptiveRetrievalProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean shadowEnabled,
        @DefaultValue("0.65") @DecimalMin("0.0") @DecimalMax("1.0") double conceptConfidenceThreshold,
        @DefaultValue("0.95") @DecimalMin("0.0") @DecimalMax("1.0") double exactConceptConfidenceThreshold
) {
    public AdaptiveRetrievalProperties(
            boolean shadowEnabled,
            double conceptConfidenceThreshold,
            double exactConceptConfidenceThreshold
    ) {
        this(
                false,
                shadowEnabled,
                conceptConfidenceThreshold,
                exactConceptConfidenceThreshold
        );
    }

    public AdaptiveRetrievalProperties {
        if (exactConceptConfidenceThreshold < conceptConfidenceThreshold) {
            throw new IllegalArgumentException(
                    "exact-concept-confidence-threshold must be >= concept-confidence-threshold"
            );
        }
    }

    public static AdaptiveRetrievalProperties defaults() {
        return new AdaptiveRetrievalProperties(false, false, 0.65, 0.95);
    }
}
