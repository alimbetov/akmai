package kz.alimbetov.akmai.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "akmai.api")
public record ApiProperties(
        @Min(1) @Max(20_000_000) int maxRequestBytes,
        @Min(1) @Max(10_000_000) int maxDocumentChars,
        @Min(1) @Max(100_000) int maxQuestionChars,
        @Min(1) @Max(10_000_000) int maxMetadataBytes,
        @Min(1) @Max(10_000) int maxMetadataEntries,
        @Min(1) @Max(64) int maxMetadataDepth,
        @Min(1) @Max(10_000) int maxTitleChars,
        @Min(1) @Max(10_000) int maxSourceChars
) {
    public ApiProperties(
            int maxDocumentChars,
            int maxQuestionChars,
            int maxMetadataBytes,
            int maxMetadataEntries,
            int maxMetadataDepth,
            int maxTitleChars,
            int maxSourceChars
    ) {
        this(
                Math.min(
                        20_000_000,
                        Math.max(
                                1,
                                maxDocumentChars + maxMetadataBytes
                                        + maxTitleChars + maxSourceChars
                                        + 65_536
                        )
                ),
                maxDocumentChars,
                maxQuestionChars,
                maxMetadataBytes,
                maxMetadataEntries,
                maxMetadataDepth,
                maxTitleChars,
                maxSourceChars
        );
    }
}
