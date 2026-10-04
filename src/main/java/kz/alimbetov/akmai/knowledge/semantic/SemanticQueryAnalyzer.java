package kz.alimbetov.akmai.knowledge.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import kz.alimbetov.akmai.rag.query.QueryLanguageDetector;
import org.springframework.stereotype.Component;

@Component
public class SemanticQueryAnalyzer {

    private final QueryLanguageDetector languageDetector;
    private final SemanticConceptMatcher conceptMatcher;
    private final SemanticDomainRouter domainRouter;

    public SemanticQueryAnalyzer(
            QueryLanguageDetector languageDetector,
            SemanticConceptMatcher conceptMatcher,
            SemanticDomainRouter domainRouter
    ) {
        this.languageDetector = languageDetector;
        this.conceptMatcher = conceptMatcher;
        this.domainRouter = domainRouter;
    }

    public SemanticQueryAnalysis analyze(String question) {
        var languageDecision = languageDetector.decision(question);
        String detected = languageDecision.primary();

        if (conceptMatcher.supports(detected)) {
            return analysisForLanguage(
                    question,
                    detected,
                    detected,
                    languageDecision.confidence()
            );
        }

        List<String> candidates =
                semanticLanguageCandidates(question);
        Candidate best = candidates.stream()
                .map(language -> new Candidate(
                        language,
                        conceptMatcher.match(question, language)
                ))
                .filter(candidate ->
                        !candidate.matches().isEmpty()
                )
                .max(
                        Comparator.comparingDouble(
                                        Candidate::score
                                )
                                .thenComparing(Candidate::language)
                )
                .orElse(null);

        if (best == null) {
            return new SemanticQueryAnalysis(
                    detected,
                    "unknown",
                    0.0,
                    List.of(),
                    List.of()
            );
        }

        return analysisForMatches(
                question,
                detected,
                best.language(),
                best.matches()
        );
    }

    private SemanticQueryAnalysis analysisForLanguage(
            String question,
            String detected,
            String language,
            double languageConfidence
    ) {
        List<SemanticConceptMatch> matches =
                conceptMatcher.match(question, language);
        if (matches.isEmpty()) {
            var domainProfile =
                    domainRouter.analyze(question, language);
            return new SemanticQueryAnalysis(
                    detected,
                    language,
                    languageConfidence,
                    domainProfile.domains().stream()
                            .map(SemanticDomainScore::domainId)
                            .toList(),
                    List.of()
            );
        }
        return analysisForMatches(
                question,
                detected,
                language,
                matches
        );
    }

    private SemanticQueryAnalysis analysisForMatches(
            String question,
            String detected,
            String language,
            List<SemanticConceptMatch> matches
    ) {
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        matches.stream()
                .sorted(
                        Comparator.comparingDouble(
                                        SemanticConceptMatch::weight
                                )
                                .reversed()
                                .thenComparing(
                                        SemanticConceptMatch::conceptId
                                )
                )
                .forEach(match ->
                        domains.add(match.domainId())
                );

        domainRouter.analyze(question, language)
                .domains()
                .stream()
                .map(SemanticDomainScore::domainId)
                .forEach(domains::add);

        double confidence = matches.stream()
                .map(SemanticConceptMatch::matchMode)
                .mapToDouble(SemanticMatchMode::confidence)
                .max()
                .orElse(0.0);

        return new SemanticQueryAnalysis(
                detected,
                language,
                confidence,
                List.copyOf(domains),
                List.copyOf(matches)
        );
    }

    private List<String> semanticLanguageCandidates(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        boolean cyrillic = text.codePoints()
                .anyMatch(codePoint ->
                        Character.UnicodeScript.of(codePoint)
                                == Character.UnicodeScript.CYRILLIC
                );
        if (cyrillic) {
            List<String> result = new ArrayList<>();
            if (conceptMatcher.supports("ru")) {
                result.add("ru");
            }
            if (conceptMatcher.supports("kk")) {
                result.add("kk");
            }
            return List.copyOf(result);
        }

        boolean latin = text.codePoints()
                .anyMatch(codePoint ->
                        Character.UnicodeScript.of(codePoint)
                                == Character.UnicodeScript.LATIN
                );
        if (latin && conceptMatcher.supports("en")) {
            return List.of("en");
        }
        return List.of();
    }

    private record Candidate(
            String language,
            List<SemanticConceptMatch> matches
    ) {
        double score() {
            return matches.stream()
                    .mapToDouble(SemanticConceptMatch::weight)
                    .sum();
        }
    }
}
