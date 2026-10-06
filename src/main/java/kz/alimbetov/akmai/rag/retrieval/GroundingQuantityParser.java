package kz.alimbetov.akmai.rag.retrieval;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative locale-aware parser for numeric grounding claims.
 *
 * <p>The parser intentionally recognizes only deterministic numeric formats.
 * Unsupported or locale-inconsistent forms are retained as invalid claims so
 * they can never satisfy the grounding fence by accident.</p>
 */
final class GroundingQuantityParser {

    private static final String NUMBER_TOKEN =
            "[-+]?(?:\\d{1,3}(?:[.,\\u00A0\\u202F ]\\d{3})+"
                    + "(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?)";
    private static final Pattern QUANTITY = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([€$£₸¥₽])?\\s*(" + NUMBER_TOKEN + ")"
                    + "(?:\\s*([\\p{L}%‰°µμ€$£₸¥₽]+(?:/[\\p{L}]+)?))?"
    );
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(\\d{1,4})([-/.])(\\d{1,2})\\2(\\d{1,4})(?!\\d)"
    );
    private static final Pattern SPACE_GROUPED = Pattern.compile(
            "\\d{1,3}(?: \\d{3})+(?:([.,])\\d+)?"
    );
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern DOT_DECIMAL = Pattern.compile("\\d+\\.\\d+");
    private static final Pattern COMMA_DECIMAL = Pattern.compile("\\d+,\\d+");
    private static final Pattern COMMA_GROUPED = Pattern.compile(
            "\\d{1,3}(?:,\\d{3})+"
    );
    private static final Pattern DOT_GROUPED = Pattern.compile(
            "\\d{1,3}(?:\\.\\d{3})+"
    );
    private static final Pattern EN_MIXED = Pattern.compile(
            "\\d{1,3}(?:,\\d{3})+\\.\\d+"
    );
    private static final Pattern COMMA_MIXED = Pattern.compile(
            "\\d{1,3}(?:\\.\\d{3})+,\\d+"
    );

    private static final Map<String, UnitSpec> UNITS = Map.ofEntries(
            alias("ug", Dimension.MASS, "mg", "0.001"),
            alias("mcg", Dimension.MASS, "mg", "0.001"),
            alias("µg", Dimension.MASS, "mg", "0.001"),
            alias("μg", Dimension.MASS, "mg", "0.001"),
            alias("мкг", Dimension.MASS, "mg", "0.001"),
            alias("微克", Dimension.MASS, "mg", "0.001"),
            alias("mg", Dimension.MASS, "mg", "1"),
            alias("мг", Dimension.MASS, "mg", "1"),
            alias("毫克", Dimension.MASS, "mg", "1"),
            alias("g", Dimension.MASS, "mg", "1000"),
            alias("г", Dimension.MASS, "mg", "1000"),
            alias("克", Dimension.MASS, "mg", "1000"),
            alias("kg", Dimension.MASS, "mg", "1000000"),
            alias("кг", Dimension.MASS, "mg", "1000000"),
            alias("千克", Dimension.MASS, "mg", "1000000"),
            alias("ml", Dimension.VOLUME, "ml", "1"),
            alias("мл", Dimension.VOLUME, "ml", "1"),
            alias("毫升", Dimension.VOLUME, "ml", "1"),
            alias("l", Dimension.VOLUME, "ml", "1000"),
            alias("л", Dimension.VOLUME, "ml", "1000"),
            alias("升", Dimension.VOLUME, "ml", "1000"),
            alias("second", Dimension.TIME, "s", "1"),
            alias("seconds", Dimension.TIME, "s", "1"),
            alias("секунда", Dimension.TIME, "s", "1"),
            alias("секунд", Dimension.TIME, "s", "1"),
            alias("秒", Dimension.TIME, "s", "1"),
            alias("minute", Dimension.TIME, "s", "60"),
            alias("minutes", Dimension.TIME, "s", "60"),
            alias("минута", Dimension.TIME, "s", "60"),
            alias("минут", Dimension.TIME, "s", "60"),
            alias("分钟", Dimension.TIME, "s", "60"),
            alias("hour", Dimension.TIME, "s", "3600"),
            alias("hours", Dimension.TIME, "s", "3600"),
            alias("час", Dimension.TIME, "s", "3600"),
            alias("часов", Dimension.TIME, "s", "3600"),
            alias("小时", Dimension.TIME, "s", "3600"),
            alias("day", Dimension.TIME, "s", "86400"),
            alias("days", Dimension.TIME, "s", "86400"),
            alias("күн", Dimension.TIME, "s", "86400"),
            alias("күндер", Dimension.TIME, "s", "86400"),
            alias("день", Dimension.TIME, "s", "86400"),
            alias("дня", Dimension.TIME, "s", "86400"),
            alias("дней", Dimension.TIME, "s", "86400"),
            alias("天", Dimension.TIME, "s", "86400"),
            alias("日", Dimension.TIME, "s", "86400"),
            alias("tag", Dimension.TIME, "s", "86400"),
            alias("tage", Dimension.TIME, "s", "86400"),
            alias("tagen", Dimension.TIME, "s", "86400"),
            alias("jour", Dimension.TIME, "s", "86400"),
            alias("jours", Dimension.TIME, "s", "86400"),
            alias("día", Dimension.TIME, "s", "86400"),
            alias("días", Dimension.TIME, "s", "86400"),
            alias("dia", Dimension.TIME, "s", "86400"),
            alias("dias", Dimension.TIME, "s", "86400"),
            alias("giorno", Dimension.TIME, "s", "86400"),
            alias("giorni", Dimension.TIME, "s", "86400"),
            alias("gün", Dimension.TIME, "s", "86400"),
            alias("ημέρα", Dimension.TIME, "s", "86400"),
            alias("ημέρες", Dimension.TIME, "s", "86400"),
            alias("week", Dimension.TIME, "s", "604800"),
            alias("weeks", Dimension.TIME, "s", "604800"),
            alias("неделя", Dimension.TIME, "s", "604800"),
            alias("недель", Dimension.TIME, "s", "604800"),
            alias("апта", Dimension.TIME, "s", "604800"),
            alias("周", Dimension.TIME, "s", "604800"),
            alias("woche", Dimension.TIME, "s", "604800"),
            alias("wochen", Dimension.TIME, "s", "604800"),
            alias("semaine", Dimension.TIME, "s", "604800"),
            alias("semaines", Dimension.TIME, "s", "604800"),
            alias("semana", Dimension.TIME, "s", "604800"),
            alias("semanas", Dimension.TIME, "s", "604800"),
            alias("settimana", Dimension.TIME, "s", "604800"),
            alias("settimane", Dimension.TIME, "s", "604800"),
            alias("hafta", Dimension.TIME, "s", "604800"),
            alias("εβδομάδα", Dimension.TIME, "s", "604800"),
            alias("εβδομάδες", Dimension.TIME, "s", "604800"),
            alias("%", Dimension.PERCENT, "%", "1"),
            alias("usd", Dimension.CURRENCY, "USD", "1"),
            alias("eur", Dimension.CURRENCY, "EUR", "1"),
            alias("kzt", Dimension.CURRENCY, "KZT", "1"),
            alias("rub", Dimension.CURRENCY, "RUB", "1"),
            alias("gbp", Dimension.CURRENCY, "GBP", "1"),
            alias("cny", Dimension.CURRENCY, "CNY", "1"),
            alias("jpy", Dimension.CURRENCY, "JPY", "1"),
            alias("€", Dimension.CURRENCY, "EUR", "1"),
            alias("₸", Dimension.CURRENCY, "KZT", "1"),
            alias("₽", Dimension.CURRENCY, "RUB", "1"),
            alias("£", Dimension.CURRENCY, "GBP", "1"),
            alias("$", Dimension.CURRENCY, "$", "1"),
            alias("¥", Dimension.CURRENCY, "¥", "1")
    );

    private static final List<String> MINIMUM_MARKERS = List.of(
            "minimum", "at least", "no less than",
            "минимум", "не менее", "кемінде", "ең аз",
            "mindestens", "au moins", "al menos", "pelo menos",
            "almeno", "en az", "τουλάχιστον", "至少", "最少"
    );
    private static final List<String> MAXIMUM_MARKERS = List.of(
            "maximum", "at most", "no more than",
            "максимум", "не более", "ең көп", "аспайды",
            "höchstens", "maximal", "au plus", "como máximo",
            "no máximo", "al massimo", "en fazla", "το πολύ",
            "最多", "不超过"
    );

    List<NumericClaim> parse(String text, String language) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalizedLanguage = normalizeLanguage(language);
        List<PositionedClaim> positioned = new ArrayList<>();
        List<Span> dateSpans = new ArrayList<>();

        Matcher dateMatcher = DATE.matcher(text);
        while (dateMatcher.find()) {
            NumericClaim claim = dateClaim(dateMatcher, normalizedLanguage);
            positioned.add(new PositionedClaim(dateMatcher.start(), claim));
            dateSpans.add(new Span(dateMatcher.start(), dateMatcher.end()));
        }

        Matcher quantityMatcher = QUANTITY.matcher(text);
        while (quantityMatcher.find()) {
            if (overlaps(dateSpans, quantityMatcher.start(), quantityMatcher.end())) {
                continue;
            }
            positioned.add(new PositionedClaim(
                    quantityMatcher.start(),
                    quantityClaim(text, quantityMatcher, normalizedLanguage)
            ));
        }

        return positioned.stream()
                .sorted(Comparator.comparingInt(PositionedClaim::position))
                .map(PositionedClaim::claim)
                .toList();
    }

    private NumericClaim dateClaim(Matcher matcher, String language) {
        String lexical = matcher.group();
        int first = parseInt(matcher.group(1));
        int middle = parseInt(matcher.group(3));
        int last = parseInt(matcher.group(4));
        LocalDate date = null;
        boolean valid = false;
        try {
            if (matcher.group(1).length() == 4) {
                date = LocalDate.of(first, middle, last);
                valid = true;
            } else if (matcher.group(4).length() == 4) {
                DateOrder order = dateOrder(language);
                if (order == DateOrder.MDY) {
                    date = LocalDate.of(last, first, middle);
                    valid = true;
                } else if (order == DateOrder.DMY) {
                    date = LocalDate.of(last, middle, first);
                    valid = true;
                }
            }
        } catch (DateTimeException ignored) {
            valid = false;
        }
        return NumericClaim.date(valid, date, lexical);
    }

    private NumericClaim quantityClaim(
            String text,
            Matcher matcher,
            String language
    ) {
        ParsedNumber parsed = parseNumber(matcher.group(2), language);
        UnitSpec prefix = unit(matcher.group(1));
        UnitSpec suffix = unit(matcher.group(3));
        UnitSpec spec = mergeUnits(prefix, suffix);
        boolean valid = parsed.valid() && spec.valid();
        BigDecimal canonical = valid
                ? parsed.value().multiply(spec.factor()).stripTrailingZeros()
                : null;
        SemanticRole role = roleFor(
                text,
                matcher.start(),
                spec.dimension()
        );
        return NumericClaim.quantity(
                valid,
                canonical,
                spec.canonicalUnit(),
                spec.dimension(),
                role,
                matcher.group()
        );
    }

    private ParsedNumber parseNumber(String raw, String language) {
        if (raw == null || raw.isBlank()) {
            return ParsedNumber.invalid();
        }
        String normalized = raw.trim()
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ');
        boolean negative = normalized.startsWith("-");
        if (negative || normalized.startsWith("+")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return ParsedNumber.invalid();
        }

        NumberPolicy policy = numberPolicy(language);
        String canonical;
        if (normalized.indexOf(' ') >= 0) {
            canonical = parseSpaceGrouped(normalized, policy);
        } else {
            canonical = switch (policy) {
                case DOT_DECIMAL_COMMA_GROUPING -> parseDotDecimal(normalized);
                case COMMA_DECIMAL_DOT_GROUPING -> parseCommaDecimal(normalized);
                case COMMA_DECIMAL_SPACE_GROUPING ->
                        parseCommaSpaceDecimal(normalized);
                case UNKNOWN -> DIGITS.matcher(normalized).matches()
                        ? normalized
                        : null;
            };
        }
        if (canonical == null) {
            return ParsedNumber.invalid();
        }
        if (negative) {
            canonical = "-" + canonical;
        }
        try {
            return new ParsedNumber(
                    true,
                    new BigDecimal(canonical).stripTrailingZeros()
            );
        } catch (NumberFormatException exception) {
            return ParsedNumber.invalid();
        }
    }

    private String parseSpaceGrouped(String value, NumberPolicy policy) {
        Matcher matcher = SPACE_GROUPED.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        String decimal = matcher.group(1);
        if (decimal != null) {
            if (policy == NumberPolicy.UNKNOWN) {
                return null;
            }
            char expected = policy == NumberPolicy.DOT_DECIMAL_COMMA_GROUPING
                    ? '.'
                    : ',';
            if (decimal.charAt(0) != expected) {
                return null;
            }
        }
        String compact = value.replace(" ", "");
        if (policy == NumberPolicy.DOT_DECIMAL_COMMA_GROUPING) {
            return compact;
        }
        if (policy == NumberPolicy.COMMA_DECIMAL_DOT_GROUPING
                || policy == NumberPolicy.COMMA_DECIMAL_SPACE_GROUPING) {
            return compact.replace(',', '.');
        }
        return null;
    }

    private String parseDotDecimal(String value) {
        if (DIGITS.matcher(value).matches()) {
            return value;
        }
        if (EN_MIXED.matcher(value).matches()) {
            return value.replace(",", "");
        }
        if (COMMA_GROUPED.matcher(value).matches()) {
            return value.replace(",", "");
        }
        if (DOT_DECIMAL.matcher(value).matches()) {
            return value;
        }
        return null;
    }

    private String parseCommaDecimal(String value) {
        if (DIGITS.matcher(value).matches()) {
            return value;
        }
        if (COMMA_MIXED.matcher(value).matches()) {
            return value.replace(".", "").replace(',', '.');
        }
        if (DOT_GROUPED.matcher(value).matches()) {
            return value.replace(".", "");
        }
        if (COMMA_DECIMAL.matcher(value).matches()) {
            return value.replace(',', '.');
        }
        return null;
    }

    private String parseCommaSpaceDecimal(String value) {
        if (DIGITS.matcher(value).matches()) {
            return value;
        }
        if (COMMA_DECIMAL.matcher(value).matches()) {
            return value.replace(',', '.');
        }
        return null;
    }

    private UnitSpec mergeUnits(UnitSpec prefix, UnitSpec suffix) {
        if (prefix.dimension() == Dimension.NONE) {
            return suffix;
        }
        if (suffix.dimension() == Dimension.NONE) {
            return prefix;
        }
        if (prefix.dimension() == Dimension.CURRENCY
                && suffix.dimension() == Dimension.CURRENCY
                && prefix.canonicalUnit().equals(suffix.canonicalUnit())) {
            return prefix;
        }
        return UnitSpec.invalid();
    }

    private UnitSpec unit(String raw) {
        if (raw == null || raw.isBlank()) {
            return UnitSpec.none();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        UnitSpec known = UNITS.get(key);
        if (known != null) {
            return known;
        }
        return new UnitSpec(true, Dimension.OTHER, key, BigDecimal.ONE);
    }

    private SemanticRole roleFor(
            String text,
            int quantityStart,
            Dimension dimension
    ) {
        int from = Math.max(0, quantityStart - 48);
        String prefix = text.substring(from, quantityStart)
                .toLowerCase(Locale.ROOT);
        MarkerRole markerRole = nearestMarker(prefix);
        if (markerRole == MarkerRole.MINIMUM) {
            return SemanticRole.MINIMUM;
        }
        if (markerRole == MarkerRole.MAXIMUM) {
            return SemanticRole.MAXIMUM;
        }
        return switch (dimension) {
            case TIME -> SemanticRole.DURATION;
            case CURRENCY -> SemanticRole.MONEY;
            case PERCENT -> SemanticRole.PERCENTAGE;
            case MASS -> SemanticRole.MASS;
            case VOLUME -> SemanticRole.VOLUME;
            default -> SemanticRole.GENERIC;
        };
    }

    private MarkerRole nearestMarker(String prefix) {
        int minIndex = lastIndexOfAny(prefix, MINIMUM_MARKERS);
        int maxIndex = lastIndexOfAny(prefix, MAXIMUM_MARKERS);
        if (minIndex < 0 && maxIndex < 0) {
            return MarkerRole.NONE;
        }
        int markerIndex = Math.max(minIndex, maxIndex);
        String trailing = prefix.substring(markerIndex);
        if (trailing.codePoints().anyMatch(Character::isDigit)) {
            return MarkerRole.NONE;
        }
        return minIndex > maxIndex
                ? MarkerRole.MINIMUM
                : MarkerRole.MAXIMUM;
    }

    private int lastIndexOfAny(String value, List<String> markers) {
        int latest = -1;
        for (String marker : markers) {
            latest = Math.max(latest, value.lastIndexOf(marker));
        }
        return latest;
    }

    private boolean overlaps(List<Span> spans, int start, int end) {
        return spans.stream().anyMatch(span -> start < span.end()
                && end > span.start());
    }

    private NumberPolicy numberPolicy(String language) {
        return switch (language) {
            case "en", "zh" -> NumberPolicy.DOT_DECIMAL_COMMA_GROUPING;
            case "de", "es", "pt", "it", "tr", "el" ->
                    NumberPolicy.COMMA_DECIMAL_DOT_GROUPING;
            case "kk", "ru", "fr" ->
                    NumberPolicy.COMMA_DECIMAL_SPACE_GROUPING;
            default -> NumberPolicy.UNKNOWN;
        };
    }

    private DateOrder dateOrder(String language) {
        if ("en".equals(language)) {
            return DateOrder.MDY;
        }
        if (List.of("kk", "ru", "de", "fr", "es", "pt", "it", "tr", "el")
                .contains(language)) {
            return DateOrder.DMY;
        }
        return DateOrder.UNKNOWN;
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "unknown";
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        int hyphen = normalized.indexOf('-');
        int underscore = normalized.indexOf('_');
        int delimiter;
        if (hyphen < 0) {
            delimiter = underscore;
        } else if (underscore < 0) {
            delimiter = hyphen;
        } else {
            delimiter = Math.min(hyphen, underscore);
        }
        return delimiter > 0 ? normalized.substring(0, delimiter) : normalized;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static Map.Entry<String, UnitSpec> alias(
            String alias,
            Dimension dimension,
            String canonicalUnit,
            String factor
    ) {
        return Map.entry(
                alias,
                new UnitSpec(
                        true,
                        dimension,
                        canonicalUnit,
                        new BigDecimal(factor)
                )
        );
    }

    record NumericClaim(
            ClaimKind kind,
            boolean valid,
            BigDecimal canonicalValue,
            String canonicalUnit,
            Dimension dimension,
            SemanticRole role,
            LocalDate date,
            String lexical
    ) {
        static NumericClaim date(
                boolean valid,
                LocalDate date,
                String lexical
        ) {
            return new NumericClaim(
                    ClaimKind.DATE,
                    valid,
                    null,
                    "date",
                    Dimension.DATE,
                    SemanticRole.DATE,
                    date,
                    lexical
            );
        }

        static NumericClaim quantity(
                boolean valid,
                BigDecimal value,
                String unit,
                Dimension dimension,
                SemanticRole role,
                String lexical
        ) {
            return new NumericClaim(
                    ClaimKind.QUANTITY,
                    valid,
                    value,
                    unit,
                    dimension,
                    role,
                    null,
                    lexical
            );
        }

        boolean matches(NumericClaim other) {
            if (other == null || !valid || !other.valid || kind != other.kind) {
                return false;
            }
            if (kind == ClaimKind.DATE) {
                return date != null && date.equals(other.date);
            }
            if (dimension != other.dimension || role != other.role) {
                return false;
            }
            if ((dimension == Dimension.CURRENCY
                    || dimension == Dimension.OTHER)
                    && !canonicalUnit.equals(other.canonicalUnit)) {
                return false;
            }
            return canonicalValue != null
                    && other.canonicalValue != null
                    && canonicalValue.compareTo(other.canonicalValue) == 0;
        }
    }

    enum ClaimKind {
        QUANTITY,
        DATE
    }

    enum Dimension {
        NONE,
        MASS,
        VOLUME,
        TIME,
        PERCENT,
        CURRENCY,
        OTHER,
        DATE
    }

    enum SemanticRole {
        GENERIC,
        MASS,
        VOLUME,
        DURATION,
        PERCENTAGE,
        MONEY,
        MINIMUM,
        MAXIMUM,
        DATE
    }

    private enum NumberPolicy {
        DOT_DECIMAL_COMMA_GROUPING,
        COMMA_DECIMAL_DOT_GROUPING,
        COMMA_DECIMAL_SPACE_GROUPING,
        UNKNOWN
    }

    private enum DateOrder {
        MDY,
        DMY,
        UNKNOWN
    }

    private enum MarkerRole {
        NONE,
        MINIMUM,
        MAXIMUM
    }

    private record UnitSpec(
            boolean valid,
            Dimension dimension,
            String canonicalUnit,
            BigDecimal factor
    ) {
        static UnitSpec none() {
            return new UnitSpec(
                    true,
                    Dimension.NONE,
                    "",
                    BigDecimal.ONE
            );
        }

        static UnitSpec invalid() {
            return new UnitSpec(
                    false,
                    Dimension.OTHER,
                    "",
                    BigDecimal.ONE
            );
        }
    }

    private record ParsedNumber(boolean valid, BigDecimal value) {
        static ParsedNumber invalid() {
            return new ParsedNumber(false, null);
        }
    }

    private record PositionedClaim(int position, NumericClaim claim) {
    }

    private record Span(int start, int end) {
    }
}
