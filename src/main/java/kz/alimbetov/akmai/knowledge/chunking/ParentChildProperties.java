package kz.alimbetov.akmai.knowledge.chunking;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "akmai.parent-child")
public record ParentChildProperties(
        boolean enabled,
        @Min(1) int childMinTokens,
        @Min(1) int childTargetTokens,
        @Min(1) int childMaxTokens,
        boolean expansionEnabled,
        @Min(1) @Max(100) int maxParentExpansions
) {
    public ParentChildProperties {
        if (childMinTokens <= 0
                || childTargetTokens <= 0
                || childMaxTokens <= 0) {
            throw new IllegalArgumentException(
                    "parent-child token limits must be positive"
            );
        }
        if (childMinTokens > childTargetTokens
                || childTargetTokens > childMaxTokens) {
            throw new IllegalArgumentException(
                    "parent-child must satisfy child-min <= child-target <= child-max"
            );
        }
        if (maxParentExpansions <= 0) {
            throw new IllegalArgumentException(
                    "max-parent-expansions must be positive"
            );
        }
    }

    public static ParentChildProperties disabled() {
        return new ParentChildProperties(
                false,
                250,
                275,
                300,
                false,
                8
        );
    }
}
