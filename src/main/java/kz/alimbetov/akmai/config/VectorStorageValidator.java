package kz.alimbetov.akmai.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class VectorStorageValidator implements ApplicationRunner {

    private static final int PGVECTOR_HNSW_MAX_DIMENSIONS = 2000;

    private final VectorStorageProperties properties;

    public VectorStorageValidator(VectorStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.dimensions() == null || properties.dimensions() < 1) {
            throw new IllegalStateException("Vector dimensions must be positive");
        }
        if ("HNSW".equalsIgnoreCase(properties.indexType())
                && properties.dimensions() > PGVECTOR_HNSW_MAX_DIMENSIONS) {
            throw new IllegalStateException(
                    "HNSW supports at most 2000 dimensions in pgvector; configured: "
                            + properties.dimensions()
            );
        }
    }
}
