package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic post-generation grounding guard.
 *
 * <p>This verifier is deliberately fail-closed. Every factual sentence must
 * cite context and every numeric claim must be supported by an ordered typed
 * quantity/date sequence in at least one cited chunk. Numeric parsing is
 * locale-aware: answer values use the query language while evidence values
 * use the source language carried by retrieval metadata. It is not a semantic
 * NLI model.</p>
 */
@Component
public class AnswerGroundingVerifier {

    private static final Pattern SOURCE =
            Pattern.compile("\\[SOURCE\\s+(\\d+)]");
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("\\R+|(?<=[.!?。！？])\\s+|(?<=[。！？])");

    private final GroundingQuantityParser quantityParser =
            new GroundingQuantityParser();

    /**
     * Compatibility overload for callers that do not yet carry query language.
     * It is conservative: language is inferred only when all cited context uses
     * one source language, otherwise locale-sensitive forms fail closed.
     */
    public GroundingValidation verify(
            String answer,
            List<RetrievalHit> context
    ) {
        return verify(answer, context, inferSingleContextLanguage(context));
    }

    public GroundingValidation verify(
            String answer,
            List<RetrievalHit> context,
            String queryLanguage
    ) {
        String input = answer == null ? "" : answer.trim();
        List<RetrievalHit> safeContext =
                context == null ? List.of() : List.copyOf(context);
        if (input.isBlank() || safeContext.isEmpty()) {
            return new GroundingValidation(false, 0, 0, List.of());
        }

        int unsupportedClaims = 0;
        int numericMismatches = 0;
        List<ClaimValidation> claims = new ArrayList<>();

        for (String raw : SENTENCE_BOUNDARY.split(input)) {
            String sentence = raw == null ? "" : raw.trim();
            String claimText = SOURCE.matcher(sentence)
                    .replaceAll("")
                    .trim();
            if (!hasClaimContent(claimText)) {
                continue;
            }

            Set<Integer> citations = citations(sentence, safeContext.size());
            if (citations.isEmpty()) {
                unsupportedClaims++;
                claims.add(new ClaimValidation(
                        claimText,
                        List.of(),
                        ClaimStatus.MISSING_CITATION
                ));
                continue;
            }

            List<GroundingQuantityParser.NumericClaim> claimNumbers =
                    quantityParser.parse(claimText, queryLanguage);
            if (!claimNumbers.isEmpty()
                    && !supportedByAnyCitation(
                            claimNumbers,
                            citations,
                            safeContext
                    )) {
                numericMismatches++;
                claims.add(new ClaimValidation(
                        claimText,
                        List.copyOf(citations),
                        ClaimStatus.NUMERIC_MISMATCH
                ));
                continue;
            }

            claims.add(new ClaimValidation(
                    claimText,
                    List.copyOf(citations),
                    ClaimStatus.SUPPORTED
            ));
        }

        boolean grounded = !claims.isEmpty()
                && unsupportedClaims == 0
                && numericMismatches == 0;
        return new GroundingValidation(
                grounded,
                unsupportedClaims,
                numericMismatches,
                List.copyOf(claims)
        );
    }

    private boolean supportedByAnyCitation(
            List<GroundingQuantityParser.NumericClaim> claimNumbers,
            Set<Integer> citations,
            List<RetrievalHit> context
    ) {
        for (int citation : citations) {
            RetrievalHit hit = context.get(citation - 1);
            List<GroundingQuantityParser.NumericClaim> evidence =
                    quantityParser.parse(hit.text(), sourceLanguage(hit));
            if (containsOrderedSequence(evidence, claimNumbers)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsOrderedSequence(
            List<GroundingQuantityParser.NumericClaim> evidence,
            List<GroundingQuantityParser.NumericClaim> claims
    ) {
        if (claims.isEmpty()) {
            return true;
        }
        int claimIndex = 0;
        for (GroundingQuantityParser.NumericClaim candidate : evidence) {
            if (candidate.matches(claims.get(claimIndex))) {
                claimIndex++;
                if (claimIndex == claims.size()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasClaimContent(String value) {
        return value != null && value.codePoints().anyMatch(
                Character::isLetterOrDigit
        );
    }

    private Set<Integer> citations(String sentence, int contextSize) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        Matcher matcher = SOURCE.matcher(sentence);
        while (matcher.find()) {
            try {
                int number = Integer.parseInt(matcher.group(1));
                if (number >= 1 && number <= contextSize) {
                    result.add(number);
                }
            } catch (NumberFormatException ignored) {
                // CitationValidator already sanitizes oversized markers.
            }
        }
        return Set.copyOf(result);
    }

    private String inferSingleContextLanguage(List<RetrievalHit> context) {
        if (context == null || context.isEmpty()) {
            return "unknown";
        }
        String candidate = null;
        for (RetrievalHit hit : context) {
            String language = sourceLanguage(hit);
            if (language == null || language.isBlank()
                    || "unknown".equalsIgnoreCase(language)) {
                return "unknown";
            }
            if (candidate == null) {
                candidate = language;
            } else if (!candidate.equalsIgnoreCase(language)) {
                return "unknown";
            }
        }
        return candidate == null ? "unknown" : candidate;
    }

    private String sourceLanguage(RetrievalHit hit) {
        if (hit == null || hit.metadata() == null) {
            return "unknown";
        }
        Object value = hit.metadata().get("language");
        return value instanceof String language && !language.isBlank()
                ? language
                : "unknown";
    }

    public enum ClaimStatus {
        SUPPORTED,
        MISSING_CITATION,
        NUMERIC_MISMATCH
    }

    public record ClaimValidation(
            String claim,
            List<Integer> citations,
            ClaimStatus status
    ) {
        public ClaimValidation {
            citations = citations == null
                    ? List.of()
                    : List.copyOf(citations);
        }
    }

    public record GroundingValidation(
            boolean grounded,
            int unsupportedClaimCount,
            int numericMismatchCount,
            List<ClaimValidation> claims
    ) {
        public GroundingValidation {
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }
}
