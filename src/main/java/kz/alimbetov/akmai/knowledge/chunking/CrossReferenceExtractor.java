package kz.alimbetov.akmai.knowledge.chunking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import kz.alimbetov.akmai.knowledge.chunking.reference.ChineseReferencePattern;
import kz.alimbetov.akmai.knowledge.chunking.reference.ExternalReferencePattern;
import kz.alimbetov.akmai.knowledge.chunking.reference.NamedObjectReferencePattern;
import kz.alimbetov.akmai.knowledge.chunking.reference.ReferencePattern;
import kz.alimbetov.akmai.knowledge.chunking.reference.ReferenceScopeResolver;
import kz.alimbetov.akmai.knowledge.chunking.reference.WordReferencePattern;
import kz.alimbetov.akmai.knowledge.model.RetrievalLanguageCatalog;
import kz.alimbetov.akmai.knowledge.reference.CrossReference;
import kz.alimbetov.akmai.knowledge.reference.CrossReferenceType;
import kz.alimbetov.akmai.knowledge.reference.StructuralAnchor;
import org.springframework.stereotype.Component;

@Component
public class CrossReferenceExtractor {

    private static final List<ReferencePattern> DEFAULT_PATTERNS = List.of(
            new WordReferencePattern(),
            new ChineseReferencePattern(),
            new NamedObjectReferencePattern(),
            new ExternalReferencePattern()
    );

    private final List<ReferencePattern> patterns;
    private final ReferenceScopeResolver scopeResolver;

    public CrossReferenceExtractor() {
        this(DEFAULT_PATTERNS, new ReferenceScopeResolver());
    }

    CrossReferenceExtractor(
            List<ReferencePattern> patterns,
            ReferenceScopeResolver scopeResolver
    ) {
        this.patterns = List.copyOf(patterns);
        this.scopeResolver = scopeResolver;
    }

    public List<String> extract(String text) {
        return extract(text, null);
    }

    public List<String> extract(String text, String languageHint) {
        return extractTyped(text, languageHint).stream()
                .map(CrossReference::rawValue)
                .distinct()
                .toList();
    }

    public List<CrossReference> extractTyped(String text) {
        return extractTyped(text, null);
    }

    public List<CrossReference> extractTyped(
            String text,
            String languageHint
    ) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        DeclarationMatch declaration = extractDeclaration(text, languageHint)
                .orElse(null);
        LinkedHashMap<String, CrossReference> result = new LinkedHashMap<>();

        for (ReferencePattern.RawMatch match : findAll(text)) {
            if (isDeclarationOccurrence(match, declaration)) {
                continue;
            }

            ReferenceScopeResolver.ScopeResolution scope =
                    scopeResolver.resolve(text, match);
            CrossReference reference = new CrossReference(
                    match.type(),
                    match.canonicalValue(),
                    match.rawText(),
                    effectiveLanguage(match.language(), languageHint),
                    scope.scope(),
                    scope.targetDocumentId(),
                    match.start(),
                    match.end()
            );
            add(result, reference);
        }

        return List.copyOf(result.values());
    }

    public Optional<StructuralAnchor> extractAnchor(String text) {
        return extractAnchor(text, null);
    }

    public Optional<StructuralAnchor> extractAnchor(
            String text,
            String languageHint
    ) {
        return extractDeclaration(text, languageHint)
                .map(DeclarationMatch::anchor);
    }

    private Optional<DeclarationMatch> extractDeclaration(
            String text,
            String languageHint
    ) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        int firstContent = firstNonWhitespace(text);
        if (firstContent < 0) {
            return Optional.empty();
        }
        int lineEnd = firstLineEnd(text, firstContent);
        String firstLine = text.substring(firstContent, lineEnd);

        return findAll(firstLine).stream()
                .filter(match -> match.start() == 0)
                .filter(match -> isAnchorType(match.type()))
                .findFirst()
                .map(match -> new DeclarationMatch(
                        new StructuralAnchor(
                                match.type(),
                                match.canonicalValue(),
                                match.rawText(),
                                effectiveLanguage(
                                        match.language(),
                                        languageHint
                                )
                        ),
                        firstContent + match.start(),
                        firstContent + match.end(),
                        match.type()
                ));
    }

    private List<ReferencePattern.RawMatch> findAll(String text) {
        List<ReferencePattern.RawMatch> matches = new ArrayList<>();
        for (ReferencePattern pattern : patterns) {
            matches.addAll(pattern.find(text));
        }
        matches.sort(
                Comparator.comparingInt(ReferencePattern.RawMatch::start)
                        .thenComparingInt(ReferencePattern.RawMatch::end)
                        .thenComparing(match -> match.type().name())
        );
        return List.copyOf(matches);
    }

    private boolean isDeclarationOccurrence(
            ReferencePattern.RawMatch match,
            DeclarationMatch declaration
    ) {
        return declaration != null
                && match.start() == declaration.start()
                && match.end() == declaration.end()
                && match.type() == declaration.type();
    }

    private boolean isAnchorType(CrossReferenceType type) {
        return switch (type) {
            case ARTICLE,
                    SECTION,
                    CLAUSE,
                    PARAGRAPH,
                    SUBPARAGRAPH,
                    CHAPTER,
                    PART,
                    APPENDIX,
                    TABLE,
                    FIGURE -> true;
            case EXTERNAL_LAW,
                    EXTERNAL_STANDARD,
                    EXTERNAL_TECHNICAL -> false;
        };
    }

    private String effectiveLanguage(
            String detected,
            String languageHint
    ) {
        if (languageHint == null || languageHint.isBlank()) {
            return detected;
        }
        return RetrievalLanguageCatalog.normalizeOrUnknown(languageHint);
    }

    private int firstNonWhitespace(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (!Character.isWhitespace(text.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private int firstLineEnd(String text, int start) {
        for (int index = start; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\n' || value == '\r') {
                return index;
            }
        }
        return text.length();
    }

    private void add(
            LinkedHashMap<String, CrossReference> result,
            CrossReference value
    ) {
        result.putIfAbsent(
                value.type() + "|" + value.canonicalValue()
                        + "|" + value.targetScope()
                        + "|" + value.targetDocumentId(),
                value
        );
    }

    private record DeclarationMatch(
            StructuralAnchor anchor,
            int start,
            int end,
            CrossReferenceType type
    ) {
    }
}
