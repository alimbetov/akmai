package kz.alimbetov.akmai.knowledge.semantic;

public enum SemanticMatchMode {
    EXACT(1.00),
    LEMMA(0.90),
    STEM(0.65);

    private final double confidence;

    SemanticMatchMode(double confidence) {
        this.confidence = confidence;
    }

    public double confidence() {
        return confidence;
    }
}
