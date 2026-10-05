package kz.alimbetov.akmai.rag.retrieval;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import kz.alimbetov.akmai.knowledge.projection.PublishedSearchProjectionReader;
import kz.alimbetov.akmai.knowledge.projection.SearchProjection;
import kz.alimbetov.akmai.knowledge.semantic.SemanticConceptMatch;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalysis;
import kz.alimbetov.akmai.knowledge.semantic.SemanticQueryAnalyzer;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LexicalRetrievalStrategy implements RetrievalStrategy {

    private static final Set<String> SEMANTIC_LEXICAL_LANGUAGES =
            Set.of("ru", "kk", "en");
    private static final int MAX_SEMANTIC_EXPANSIONS = 2;
    private static final int MAX_SEMANTIC_RESULT_SLOTS = 2;

    private final PublishedSearchProjectionReader repository;
    private final RetrievalProperties properties;
    private final SemanticQueryAnalyzer semanticQueryAnalyzer;

    public LexicalRetrievalStrategy(
            PublishedSearchProjectionReader repository,
            RetrievalProperties properties
    ) {
        this(repository, properties, null);
    }

    @Autowired
    public LexicalRetrievalStrategy(
            PublishedSearchProjectionReader repository,
            RetrievalProperties properties,
            SemanticQueryAnalyzer semanticQueryAnalyzer
    ) {
        this.repository = repository;
        this.properties = properties;
        this.semanticQueryAnalyzer = semanticQueryAnalyzer;
    }

    @Override
    public RetrievalType type() {
        return RetrievalType.LEXICAL;
    }

    @Override
    public List<RetrievalHit> retrieve(
            QueryChunk queryChunk,
            RetrievalContext context
    ) {
        List<String> documentIds =
                List.copyOf(context.documentIds());
        int limit = properties.lexicalLimit();

        List<SearchProjection> baseline = baselineSearch(
                queryChunk,
                documentIds,
                context.accessLevels(),
                limit
        );
        List<SearchProjection> semantic = semanticSearch(
                queryChunk,
                documentIds,
                context.accessLevels(),
                limit
        );

        List<ProjectionCandidate> projections = merge(
                baseline,
                semantic,
                limit
        );
        return projections.stream()
                .map(candidate -> toHit(
                        candidate.projection(),
                        candidate.semanticExpansion()
                ))
                .toList();
    }

    private List<SearchProjection> baselineSearch(
            QueryChunk queryChunk,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        if ("unknown".equals(queryChunk.language())
                && containsCyrillic(queryChunk.semanticText())) {
            return ambiguousCyrillicSearch(
                    queryChunk.semanticText(),
                    documentIds,
                    accessLevels
            );
        }
        return repository.searchLexical(
                queryChunk.semanticText(),
                queryChunk.language(),
                documentIds,
                accessLevels,
                limit
        );
    }

    private List<SearchProjection> semanticSearch(
            QueryChunk queryChunk,
            List<String> documentIds,
            Set<Long> accessLevels,
            int limit
    ) {
        if (semanticQueryAnalyzer == null) {
            return List.of();
        }

        SemanticQueryAnalysis analysis;
        try {
            analysis = semanticQueryAnalyzer.analyze(
                    queryChunk.semanticText()
            );
        } catch (RuntimeException exception) {
            return List.of();
        }

        String semanticLanguage = analysis.semanticLanguage();
        if (!SEMANTIC_LEXICAL_LANGUAGES.contains(semanticLanguage)) {
            return List.of();
        }
        if (!"unknown".equals(queryChunk.language())
                && !queryChunk.language().equals(semanticLanguage)) {
            return List.of();
        }

        String normalizedQuery =
                normalize(queryChunk.semanticText());
        List<String> expansions = analysis.concepts().stream()
                .sorted(
                        Comparator.comparingDouble(
                                        SemanticConceptMatch::weight
                                )
                                .reversed()
                                .thenComparing(
                                        SemanticConceptMatch::conceptId
                                )
                )
                .map(SemanticConceptMatch::phrase)
                .filter(phrase -> !phrase.isBlank())
                .filter(phrase ->
                        !containsNormalizedPhrase(
                                normalizedQuery,
                                normalize(phrase)
                        )
                )
                .distinct()
                .limit(MAX_SEMANTIC_EXPANSIONS)
                .toList();

        if (expansions.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<ProjectionKey, SearchProjection> merged =
                new LinkedHashMap<>();
        for (String expansion : expansions) {
            for (SearchProjection projection :
                    repository.searchLexical(
                            expansion,
                            semanticLanguage,
                            documentIds,
                            accessLevels,
                            limit
                    )) {
                merged.putIfAbsent(
                        ProjectionKey.of(projection),
                        projection
                );
            }
        }
        return List.copyOf(merged.values());
    }

    private List<ProjectionCandidate> merge(
            List<SearchProjection> baseline,
            List<SearchProjection> semantic,
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }

        LinkedHashMap<ProjectionKey, ProjectionCandidate> merged =
                new LinkedHashMap<>();
        int semanticSlots = semantic.isEmpty()
                ? 0
                : Math.min(
                        MAX_SEMANTIC_RESULT_SLOTS,
                        limit / 4
                );
        int baselinePrefix = Math.max(0, limit - semanticSlots);

        for (int i = 0;
                i < baseline.size()
                        && i < baselinePrefix;
                i++) {
            SearchProjection projection = baseline.get(i);
            merged.putIfAbsent(
                    ProjectionKey.of(projection),
                    new ProjectionCandidate(projection, false)
            );
        }

        int insertedSemantic = 0;
        for (SearchProjection projection : semantic) {
            if (insertedSemantic >= semanticSlots
                    || merged.size() >= limit) {
                break;
            }
            ProjectionKey key = ProjectionKey.of(projection);
            if (!merged.containsKey(key)) {
                merged.put(
                        key,
                        new ProjectionCandidate(projection, true)
                );
                insertedSemantic++;
            }
        }

        for (SearchProjection projection : baseline) {
            if (merged.size() >= limit) {
                break;
            }
            merged.putIfAbsent(
                    ProjectionKey.of(projection),
                    new ProjectionCandidate(projection, false)
            );
        }

        return List.copyOf(merged.values());
    }

    private RetrievalHit toHit(
            SearchProjection projection,
            boolean semanticExpansion
    ) {
        java.util.HashMap<String, Object> metadata =
                new java.util.HashMap<>(projection.metadata());
        metadata.put("language", projection.language());
        metadata.put("sectionPath", projection.sectionPath() == null
                ? ""
                : projection.sectionPath());
        metadata.put("chunkIndex", projection.chunkIndex());
        metadata.put("generation", projection.generation());
        if (semanticExpansion) {
            metadata.put("semanticLexicalExpansion", true);
        }
        return new RetrievalHit(
                RetrievalType.LEXICAL,
                projection.accessLevel(),
                projection.documentId(),
                projection.generation(),
                projection.chunkId(),
                projection.text(),
                metadata
        );
    }

    private List<SearchProjection> ambiguousCyrillicSearch(
            String query,
            List<String> documentIds,
            Set<Long> accessLevels
    ) {
        LinkedHashMap<ProjectionKey, SearchProjection> merged =
                new LinkedHashMap<>();
        List<SearchProjection> values = new ArrayList<>();
        values.addAll(repository.searchLexical(
                query,
                "kk",
                documentIds,
                accessLevels,
                properties.lexicalLimit()
        ));
        values.addAll(repository.searchLexical(
                query,
                "ru",
                documentIds,
                accessLevels,
                properties.lexicalLimit()
        ));
        for (SearchProjection projection : values) {
            merged.putIfAbsent(
                    ProjectionKey.of(projection),
                    projection
            );
            if (merged.size() >= properties.lexicalLimit()) {
                break;
            }
        }
        return List.copyOf(merged.values());
    }

    private boolean containsCyrillic(String value) {
        return value != null && value.codePoints().anyMatch(
                codePoint -> Character.UnicodeScript.of(codePoint)
                        == Character.UnicodeScript.CYRILLIC
        );
    }

    private String normalize(String value) {
        return Normalizer.normalize(
                        value == null ? "" : value,
                        Normalizer.Form.NFC
                )
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ");
    }

    private boolean containsNormalizedPhrase(
            String text,
            String phrase
    ) {
        if (text.isBlank() || phrase.isBlank()) {
            return false;
        }
        if (text.equals(phrase)) {
            return true;
        }
        return text.startsWith(phrase + " ")
                || text.endsWith(" " + phrase)
                || text.contains(" " + phrase + " ");
    }

    private record ProjectionCandidate(
            SearchProjection projection,
            boolean semanticExpansion
    ) {
    }

    private record ProjectionKey(
            long accessLevel,
            String documentId,
            long generation,
            String chunkId
    ) {
        static ProjectionKey of(SearchProjection projection) {
            return new ProjectionKey(
                    projection.accessLevel(),
                    projection.documentId(),
                    projection.generation(),
                    projection.chunkId()
            );
        }
    }
}
