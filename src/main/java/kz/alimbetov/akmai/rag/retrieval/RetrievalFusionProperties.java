package kz.alimbetov.akmai.rag.retrieval;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.retrieval.fusion")
public record RetrievalFusionProperties(
        @DefaultValue("false") boolean weightedEnabled,
        @DefaultValue("1.0") @DecimalMin("0.0") @DecimalMax("4.0") double identifierWeight,
        @DefaultValue("1.0") @DecimalMin("0.0") @DecimalMax("4.0") double vectorWeight,
        @DefaultValue("1.0") @DecimalMin("0.0") @DecimalMax("4.0") double lexicalWeight,
        @DefaultValue("1.0") @DecimalMin("0.0") @DecimalMax("4.0") double conceptWeight,
        @DefaultValue("1.0") @DecimalMin("0.0") @DecimalMax("4.0") double referenceWeight
) {
    public static RetrievalFusionProperties defaults() {
        return new RetrievalFusionProperties(
                false,
                1.0,
                1.0,
                1.0,
                1.0,
                1.0
        );
    }

    public double weight(RetrievalType type) {
        if (!weightedEnabled || type == null) {
            return 1.0;
        }
        return switch (type) {
            case IDENTIFIER -> identifierWeight;
            case VECTOR -> vectorWeight;
            case LEXICAL -> lexicalWeight;
            case CONCEPT -> conceptWeight;
            case REFERENCE -> referenceWeight;
            case GRAPH -> 1.0;
        };
    }
}
