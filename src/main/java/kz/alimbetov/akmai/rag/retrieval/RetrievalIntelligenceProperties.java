package kz.alimbetov.akmai.rag.retrieval;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.retrieval.intelligence")
public record RetrievalIntelligenceProperties(
        @DefaultValue("false") boolean sectionDiversityEnabled,
        @DefaultValue("2") @Min(1) @Max(20) int maxChunksPerSection,
        @DefaultValue("true") boolean evidenceQualityTelemetryEnabled
) {
    public static RetrievalIntelligenceProperties defaults() {
        return new RetrievalIntelligenceProperties(false, 2, true);
    }
}
