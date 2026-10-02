package kz.alimbetov.akmai.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "akmai.model-budget")
public record ModelBudgetProperties(
        @Min(1024) int chatContextWindow,
        @Min(256) int embeddingContextWindow
) {
}
