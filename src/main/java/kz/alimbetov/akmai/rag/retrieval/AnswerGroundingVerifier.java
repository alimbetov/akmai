package kz.alimbetov.akmai.rag.retrieval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic post-generation grounding guard.
 *
 * <p>This is deliberately conservative: every factual sentence must cite at
 * least one context source, and numeric literals in that sentence must be
 * present in at least one of its cited chunks. It is not a semantic NLI model;
 * it is the fail-closed first line of defence before richer entailment
 * verification is introduced.</p>
 */
@Component
public class AnswerGroundingVerifier {

    private static final Pattern SOURCE =
            Pattern.compile("\\[SOURCE\\s+(\\d+)]");
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("\\R+|(?<=[.!?。！？])\\s+|(?<=[。！？])");
    private static final Pattern NUMBER =
            Pattern.compile("(?<![\\p{L}\\p{N}])[-+]?\\d+(?:[.,]\\d+)?");

    public GroundingValidation verify(
            String answer,
            List<RetrievalHit> context
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

            Set<String> claimNumbers = numbers(claimText);
            if (!claimNumbers.isEmpty()) {
                Set<String> evidenceNumbers = new LinkedHashSet<>();
                for (int citation : citations) {
                    evidenceNumbers.addAll(numbers(
                            safeContext.get(citation - 1).text()
                    ));
                }
                if (!evidenceNumbers.containsAll(claimNumbers)) {
                    numericMismatches++;
                    claims.add(new ClaimValidation(
                            claimText,
                            List.copyOf(citations),
                            ClaimStatus.NUMERIC_MISMATCH
                    ));
                    continue;
                }
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

    private boolean hasClaimContent(String value) {
        return value != null && value.codePoints().anyMatch(
                codePoint -> Character.isLetterOrDigit(codePoint)
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

    private Set<String> numbers(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = NUMBER.matcher(value);
        while (matcher.find()) {
            result.add(normalizeNumber(matcher.group()));
        }
        return Set.copyOf(result);
    }

    private String normalizeNumber(String raw) {
        String value = raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace(',', '.');
        try {
            return new BigDecimal(value)
                    .stripTrailingZeros()
                    .toPlainString();
        } catch (NumberFormatException exception) {
            return value;
        }
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
