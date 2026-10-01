package kz.alimbetov.akmai.rag.retrieval;

public final class RetrievalTestProperties {

    private RetrievalTestProperties() {
    }

    public static RetrievalProperties defaults() {
        return new RetrievalProperties(
                8,
                64,
                5,
                0.65,
                10,
                10,
                20,
                60,
                5,
                1,
                10,
                6000,
                12,
                4
        );
    }
}
