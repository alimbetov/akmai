package kz.alimbetov.akmai.knowledge.vector;

final class AnnSearchTuningPolicy {

    private AnnSearchTuningPolicy() {
    }

    static AnnSearchTuning forAccessScope(int accessLevelCount) {
        if (accessLevelCount <= 0) {
            throw new IllegalArgumentException(
                    "accessLevelCount must be positive"
            );
        }
        if (accessLevelCount <= 2) {
            return new AnnSearchTuning(1, 40);
        }
        if (accessLevelCount <= 4) {
            return new AnnSearchTuning(4, 40);
        }
        return new AnnSearchTuning(2, 120);
    }

    record AnnSearchTuning(
            int candidateMultiplier,
            int efSearch
    ) {
        AnnSearchTuning {
            if (candidateMultiplier <= 0 || efSearch <= 0) {
                throw new IllegalArgumentException(
                        "ANN tuning values must be positive"
                );
            }
        }

        int candidateLimit(int topK) {
            if (topK <= 0) {
                throw new IllegalArgumentException(
                        "topK must be positive"
                );
            }
            return Math.multiplyExact(topK, candidateMultiplier);
        }
    }
}
