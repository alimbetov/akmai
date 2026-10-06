package kz.alimbetov.akmai.rag.query;

import java.nio.charset.StandardCharsets;
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
        return chunk(question, QueryOrigin.ORIGINAL);
    }

    public List<QueryChunk> chunk(
            String question,
            QueryOrigin origin
    ) {
        QueryOrigin effectiveOrigin = origin == null
                ? QueryOrigin.ORIGINAL
                : origin;
        String normalized = normalizer.normalize(question);
        QueryDecompositionResult decomposition =
                decomposer.decomposeDetailed(normalized);
        List<QueryChunk> result = new ArrayList<>();

        for (String segment : decomposition.units()) {
            if (segment.isBlank() || result.size() >= QueryDecomposer.MAX_SEGMENTS) {
                continue;
            }
            List<DetectedIdentifier> identifiers = identifierExtractor.extract(segment);
            String semantic = semanticText(segment, identifiers);
            result.add(newChunk(
                    result.size(),
                    segment,
                    semantic,
                    identifiers,
                    effectiveOrigin
            ));
        }

        return result.isEmpty()
                ? List.of(newChunk(
                        0,
                        normalized,
                        normalized,
                        List.of(),
                        effectiveOrigin
                ))
                : List.copyOf(result);
    }

    private QueryChunk newChunk(
            int index,
            String text,
            String semantic,
            List<DetectedIdentifier> identifiers,
            QueryOrigin origin
    ) {
        String normalized = normalizer.normalize(text);
        String idSource = origin.name()
                + "|"
                + index
                + "|"
                + normalized
                + "|"
                + semantic;
        return new QueryChunk(
                UUID.nameUUIDFromBytes(
                        idSource.getBytes(StandardCharsets.UTF_8)
                ).toString(),
                index,
                text,
                normalized,
                semantic.isBlank() ? text : semantic,
                languageDetector.detect(text),
                identifiers,
                origin
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
