package kz.alimbetov.akmai.rag.query;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.chunking.TextNormalizer;
import kz.alimbetov.akmai.knowledge.identifier.DetectedIdentifier;
import kz.alimbetov.akmai.knowledge.identifier.IdentifierExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class QueryChunker {

    private final TextNormalizer normalizer;
    private final IdentifierExtractor identifierExtractor;
    private final QueryLanguageDetector languageDetector;
    private final QueryDecomposer decomposer;

    public QueryChunker(
            TextNormalizer normalizer,
            IdentifierExtractor identifierExtractor,
            QueryLanguageDetector languageDetector
    ) {
        this(
                normalizer,
                identifierExtractor,
                languageDetector,
                new QueryDecomposer(normalizer)
        );
    }

    @Autowired
    public QueryChunker(
            TextNormalizer normalizer,
            IdentifierExtractor identifierExtractor,
            QueryLanguageDetector languageDetector,
            QueryDecomposer decomposer
    ) {
        this.normalizer = normalizer;
        this.identifierExtractor = identifierExtractor;
        this.languageDetector = languageDetector;
        this.decomposer = decomposer;
    }

    public List<QueryChunk> chunk(String question) {
        String normalized = normalizer.normalize(question);
        List<String> segments = decomposer.decompose(normalized);
        List<QueryChunk> result = new ArrayList<>();

        for (String segment : segments) {
            if (segment.isBlank() || result.size() >= QueryDecomposer.MAX_SEGMENTS) {
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
                if (result.size() >= QueryDecomposer.MAX_SEGMENTS) {
                    break;
                }
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
                languageDetector.detect(text),
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
