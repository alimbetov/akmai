package kz.alimbetov.akmai.config;

public final class CanonicalEmbeddingContract {

    public static final String MODEL = "qwen3-embedding:4b";
    public static final int DIMENSIONS = 1024;

    private CanonicalEmbeddingContract() {
    }

    public static void validate(String model, int dimensions) {
        if (!MODEL.equals(model)) {
            throw new IllegalStateException(
                    "Canonical embedding model must be " + MODEL
                            + ", configured=" + model
            );
        }
        if (dimensions != DIMENSIONS) {
            throw new IllegalStateException(
                    "Canonical embedding dimensions must be " + DIMENSIONS
                            + ", configured=" + dimensions
            );
        }
    }
}
