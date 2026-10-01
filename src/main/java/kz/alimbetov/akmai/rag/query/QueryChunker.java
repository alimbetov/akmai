package kz.alimbetov.akmai.rag.query;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.chunking.TextNormalizer;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import org.springframework.stereotype.Component;

@Component
public class QueryChunker {

    private final TextNormalizer normalizer;
    private final IdentifierExtractor identifierExtractor;

    public QueryChunker(
            TextNormalizer normalizer,
            IdentifierExtractor identifierExtractor
    ) {
        this.normalizer = normalizer;
        this.identifierExtractor = identifierExtractor;
    }

    public List<QueryChunk> chunk(String question) {
        String normalized = normalizer.normalize(question);
        List<String> segments = List.of(normalized.split("(?<=[.!?;])\\s+"));
        List<QueryChunk> result = new ArrayList<>();

        for (String segment : segments) {
            if (segment.isBlank()) {
                continue;
            }

            List<DetectedIdentifier> identifiers = identifierExtractor.extract(segment);
            String semantic = semanticText(segment, identifiers);

            if (identifiers.size() <= 1) {
                result.add(newChunk(result.size(), segment, semantic, identifiers));
                continue;
            }

            // Multiple independent identifiers in one clause become separate retrieval units.
            for (DetectedIdentifier identifier : identifiers) {
                result.add(newChunk(
                        result.size(),
                        segment,
                        semantic,
                        List.of(identifier)
                ));
            }
        }

        return result.isEmpty()
                ? List.of(newChunk(0, normalized, normalized, List.of()))
                : List.copyOf(result);
    }

    private QueryChunk newChunk(
            int index,
            String text,
            String semantic,
            List<DetectedIdentifier> identifiers
    ) {
        return new QueryChunk(
                UUID.randomUUID().toString(),
                index,
                text,
                normalizer.normalize(text),
                semantic.isBlank() ? text : semantic,
                identifiers
        );
    }

    private String semanticText(
            String text,
            List<DetectedIdentifier> identifiers
    ) {
        String result = text;
        for (DetectedIdentifier identifier : identifiers) {
            result = result.replace(identifier.rawValue(), " ");
        }
        return result.replaceAll("\\s+", " ").trim();
    }
}
