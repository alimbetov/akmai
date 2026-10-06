package kz.alimbetov.akmai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("akmai.semantic-memory")
public class SemanticMemoryProperties {

    private boolean ingestionLinkingEnabled = false;
    private int topK = 16;
    private int maxEdgesPerChunk = 6;
    private double minSimilarity = 0.90;
    private boolean sameLanguageOnly = true;

    public boolean isIngestionLinkingEnabled() {
        return ingestionLinkingEnabled;
    }

    public void setIngestionLinkingEnabled(boolean ingestionLinkingEnabled) {
        this.ingestionLinkingEnabled = ingestionLinkingEnabled;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        if (topK < 1 || topK > 256) {
            throw new IllegalArgumentException(
                    "semantic-memory topK must be in [1, 256]"
            );
        }
        this.topK = topK;
    }

    public int getMaxEdgesPerChunk() {
        return maxEdgesPerChunk;
    }

    public void setMaxEdgesPerChunk(int maxEdgesPerChunk) {
        if (maxEdgesPerChunk < 1 || maxEdgesPerChunk > 64) {
            throw new IllegalArgumentException(
                    "semantic-memory maxEdgesPerChunk must be in [1, 64]"
            );
        }
        this.maxEdgesPerChunk = maxEdgesPerChunk;
    }

    public double getMinSimilarity() {
        return minSimilarity;
    }

    public void setMinSimilarity(double minSimilarity) {
        if (!Double.isFinite(minSimilarity)
                || minSimilarity < 0
                || minSimilarity > 1) {
            throw new IllegalArgumentException(
                    "semantic-memory minSimilarity must be in [0, 1]"
            );
        }
        this.minSimilarity = minSimilarity;
    }

    public boolean isSameLanguageOnly() {
        return sameLanguageOnly;
    }

    public void setSameLanguageOnly(boolean sameLanguageOnly) {
        this.sameLanguageOnly = sameLanguageOnly;
    }
}
