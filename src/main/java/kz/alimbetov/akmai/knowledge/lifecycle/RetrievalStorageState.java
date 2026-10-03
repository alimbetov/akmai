package kz.alimbetov.akmai.knowledge.lifecycle;

public enum RetrievalStorageState {

    ACTIVE((short) 0),
    ARCHIVED((short) 1);

    private final short code;

    RetrievalStorageState(short code) {
        this.code = code;
    }

    public short code() {
        return code;
    }

    public static RetrievalStorageState fromCode(short code) {
        for (RetrievalStorageState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported retrieval storage state: " + code
        );
    }
}
