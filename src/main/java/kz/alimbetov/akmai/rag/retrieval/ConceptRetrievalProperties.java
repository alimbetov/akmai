package kz.alimbetov.akmai.rag.retrieval;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.retrieval.concept")
public record ConceptRetrievalProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("4") @Min(1) @Max(16) int maxQueryConcepts,
        @DefaultValue("4") @Min(1) @Max(100) int topK,
        @DefaultValue("0.65") @DecimalMin("0.0") @DecimalMax("1.0") double minConfidence
) {
    public static ConceptRetrievalProperties defaults() {
        return new ConceptRetrievalProperties(true, 4, 4, 0.65);
    }
}
