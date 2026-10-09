package kz.alimbetov.akmai.knowledge.api;

public record KnowledgeIngestionResult(
        int schemaVersion,
        String documentId,
        Source source,
        Publication publication,
        Processing processing
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public KnowledgeIngestionResult {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported result schemaVersion");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        if (publication == null) {
            throw new IllegalArgumentException("publication is required");
        }
        if (processing == null) {
            throw new IllegalArgumentException("processing is required");
        }
    }

    public record Source(
            String type,
            String fileId,
            String sourceVersion,
            String contentHash
    ) {
        public Source {
            type = requireText("source.type", type);
            fileId = requireText("source.fileId", fileId);
            sourceVersion = requireText("source.sourceVersion", sourceVersion);
            contentHash = requireText("source.contentHash", contentHash);
        }
    }

    public record Publication(
            PublicationStatus status,
            long generation,
            int chunkCount
    ) {
        public Publication {
            if (status == null) {
                throw new IllegalArgumentException("publication.status is required");
            }
            if (generation <= 0) {
                throw new IllegalArgumentException("generation must be positive");
            }
            if (chunkCount <= 0) {
                throw new IllegalArgumentException("chunkCount must be positive");
            }
        }
    }

    public enum PublicationStatus {
        PUBLISHED,
        REPLAYED,
        ALREADY_PUBLISHED
    }

    public record Processing(
            int canonicalSchemaVersion,
            String canonicalHash,
            String parser,
            String parserVersion,
            String embeddingProfile
    ) {
        public Processing {
            if (canonicalSchemaVersion <= 0) {
                throw new IllegalArgumentException(
                        "canonicalSchemaVersion must be positive"
                );
            }
            canonicalHash = requireText("processing.canonicalHash", canonicalHash);
            parser = requireText("processing.parser", parser);
            parserVersion = requireText(
                    "processing.parserVersion",
                    parserVersion
            );
            embeddingProfile = requireText(
                    "processing.embeddingProfile",
                    embeddingProfile
            );
        }
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
