package kz.alimbetov.akmai.rag.api;

import java.util.List;

public record RagResponse(
        String requestId,
        String answer,
        List<Source> sources
) {
    public RagResponse(String answer, List<Source> sources) {
        this(null, answer, sources);
    }

    public RagResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public record Source(
            int number,
            String documentId,
            String chunkId,
            String source,
            String language,
            String sectionPath,
            String page,
            Provenance provenance
    ) {
        public Source(
                int number,
                String documentId,
                String chunkId,
                String source,
                String language,
                String sectionPath,
                String page
        ) {
            this(
                    number,
                    documentId,
                    chunkId,
                    source,
                    language,
                    sectionPath,
                    page,
                    Provenance.empty()
            );
        }

        public Source {
            provenance = provenance == null ? Provenance.empty() : provenance;
        }
    }

    public record Provenance(List<CanonicalBlock> canonicalBlocks) {
        public Provenance {
            canonicalBlocks = canonicalBlocks == null
                    ? List.of()
                    : List.copyOf(canonicalBlocks);
        }

        public static Provenance empty() {
            return new Provenance(List.of());
        }
    }

    public record CanonicalBlock(
            String blockId,
            Integer pageFrom,
            Integer pageTo,
            String sectionPath,
            BoundingBox boundingBox
    ) {
    }

    public record BoundingBox(
            double x,
            double y,
            double width,
            double height
    ) {
    }
}
