package kz.alimbetov.akmai.rag.retrieval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic post-generation grounding guard.
 *
 * <p>This verifier is deliberately fail-closed. Every factual sentence must
 * cite context and every numeric claim must be supported by an ordered
 * numeric/quantity sequence in at least one cited chunk. Numeric comparison
 * keeps units attached to values and refuses to guess ambiguous locale
 * separators such as {@code 1,000}. It is not a semantic NLI model.</p>
 */
@Component
public class AnswerGroundingVerifier {

    private static final Pattern SOURCE =
            Pattern.compile("\\[SOURCE\\s+(\\d+)]");
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("\\R+|(?<=[.!?。！？])\\s+|(?<=[。！？])");
    private static final Pattern QUANTITY = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([-+]?\\d+(?:[.,]\\d+)?)(?:\\s*([\\p{L}%‰°µμ]+(?:/[\\p{L}]+)?))?"
    );

    private static final Map<String, String> UNIT_ALIASES = Map.ofEntries(
            Map.entry("mg", "mg"),
            Map.entry("мг", "mg"),
            Map.entry("毫克", "mg"),
            Map.entry("mcg", "ug"),
            Map.entry("ug", "ug"),
            Map.entry("µg", "ug"),
            Map.entry("μg", "ug"),
            Map.entry("мкг", "ug"),
            Map.entry("微克", "ug"),
            Map.entry("g", "g"),
            Map.entry("г", "g"),
            Map.entry("克", "g"),
            Map.entry("kg", "kg"),
            Map.entry("кг", "kg"),
            Map.entry("千克", "kg"),
            Map.entry("ml", "ml"),
            Map.entry("мл", "ml"),
            Map.entry("毫升", "ml"),
            Map.entry("l", "l"),
            Map.entry("л", "l"),
            Map.entry("升", "l")
    );

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

            List<NumericClaim> claimNumbers = numericClaims(claimText);
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
            List<NumericClaim> claimNumbers,
            Set<Integer> citations,
            List<RetrievalHit> context
    ) {
        for (int citation : citations) {
            List<NumericClaim> evidence = numericClaims(
                    context.get(citation - 1).text()
            );
            if (containsOrderedSequence(evidence, claimNumbers)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsOrderedSequence(
            List<NumericClaim> evidence,
            List<NumericClaim> claims
    ) {
        if (claims.isEmpty()) {
            return true;
        }
        int claimIndex = 0;
        for (NumericClaim candidate : evidence) {
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

    private List<NumericClaim> numericClaims(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<NumericClaim> result = new ArrayList<>();
        Matcher matcher = QUANTITY.matcher(value);
        while (matcher.find()) {
            result.add(new NumericClaim(
                    normalizeNumber(matcher.group(1)),
                    normalizeUnit(matcher.group(2))
            ));
        }
        return List.copyOf(result);
    }

    private String normalizeNumber(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        int comma = value.indexOf(',');
        int dot = value.indexOf('.');

        if (comma >= 0 && dot >= 0) {
            return "ambiguous:" + value;
        }

        int separator = comma >= 0 ? comma : dot;
        if (separator >= 0) {
            int fractionalDigits = value.length() - separator - 1;
            String integerPart = value.substring(
                    value.startsWith("+") || value.startsWith("-") ? 1 : 0,
                    separator
            );
            if (fractionalDigits == 3
                    && !integerPart.isEmpty()
                    && !integerPart.chars().allMatch(ch -> ch == '0')) {
                // A single separator followed by three digits is locale
                // ambiguous (1,000 may mean one thousand or one decimal).
                // Preserve it lexically instead of guessing.
                return "ambiguous:" + value;
            }
            value = value.replace(',', '.');
        }

        try {
            return new BigDecimal(value)
                    .stripTrailingZeros()
                    .toPlainString();
        } catch (NumberFormatException exception) {
            return "invalid:" + raw;
        }
    }

    private String normalizeUnit(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String unit = raw.trim().toLowerCase(Locale.ROOT);
        return UNIT_ALIASES.getOrDefault(unit, unit);
    }

    private record NumericClaim(String value, String unit) {
        private boolean matches(NumericClaim other) {
            return value.equals(other.value) && unit.equals(other.unit);
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
