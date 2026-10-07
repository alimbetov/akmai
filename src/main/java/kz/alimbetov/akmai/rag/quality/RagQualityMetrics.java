package kz.alimbetov.akmai.rag.quality;

public record RagQualityMetrics(
        double recallAt1,
        double recallAt5,
        double recallAt10,
        double mrr,
        double ndcgAt10,
        double contextRecall,
        double contextPrecision,
        double evidenceDensity,
        double abstentionPrecision,
        double abstentionRecall,
        double falseAnswerRate,
        double falseAbstentionRate
) {
    public RagQualityMetrics {
        validate("recallAt1", recallAt1);
        validate("recallAt5", recallAt5);
        validate("recallAt10", recallAt10);
        validate("mrr", mrr);
        validate("ndcgAt10", ndcgAt10);
        validate("contextRecall", contextRecall);
        validate("contextPrecision", contextPrecision);
        validate("evidenceDensity", evidenceDensity);
        validate("abstentionPrecision", abstentionPrecision);
        validate("abstentionRecall", abstentionRecall);
        validate("falseAnswerRate", falseAnswerRate);
        validate("falseAbstentionRate", falseAbstentionRate);
    }

    public double value(String metric) {
        return switch (metric) {
            case "recallAt1" -> recallAt1;
            case "recallAt5" -> recallAt5;
            case "recallAt10" -> recallAt10;
            case "mrr" -> mrr;
            case "ndcgAt10" -> ndcgAt10;
            case "contextRecall" -> contextRecall;
            case "contextPrecision" -> contextPrecision;
            case "evidenceDensity" -> evidenceDensity;
            case "abstentionPrecision" -> abstentionPrecision;
            case "abstentionRecall" -> abstentionRecall;
            case "falseAnswerRate" -> falseAnswerRate;
            case "falseAbstentionRate" -> falseAbstentionRate;
            default -> throw new IllegalArgumentException(
                    "Unknown RAG quality metric: " + metric
            );
        };
    }

    private static void validate(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be a finite value between 0 and 1"
            );
        }
    }
}
