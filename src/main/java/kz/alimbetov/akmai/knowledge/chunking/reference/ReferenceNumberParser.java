package kz.alimbetov.akmai.knowledge.chunking.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

public final class ReferenceNumberParser {

    private static final Map<Character, Integer> ROMAN_VALUES = Map.of(
            'I', 1,
            'V', 5,
            'X', 10,
            'L', 50,
            'C', 100,
            'D', 500,
            'M', 1000
    );

    private ReferenceNumberParser() {
    }

    public static String canonicalArabic(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String value = raw.trim();
        if (value.matches("[0-9]+(?:\\.[0-9]+)*")) {
            String[] parts = value.split("\\.");
            List<String> normalized = new ArrayList<>(parts.length);
            for (String part : parts) {
                normalized.add(part.replaceFirst("^0+(?!$)", ""));
            }
            return String.join(".", normalized);
        }
        return value.replaceFirst("^0+(?!$)", "");
    }

    public static OptionalInt romanToInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return OptionalInt.empty();
        }
        String value = raw.toUpperCase(Locale.ROOT);
        if (!value.matches("[IVXLCDM]+")) {
            return OptionalInt.empty();
        }

        int total = 0;
        int previous = 0;
        for (int index = value.length() - 1; index >= 0; index--) {
            int current = ROMAN_VALUES.get(value.charAt(index));
            if (current < previous) {
                total -= current;
            } else {
                total += current;
                previous = current;
            }
        }
        if (total <= 0 || total > 3999 || !toRoman(total).equals(value)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(total);
    }

    public static String canonicalChinese(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            return canonicalArabic(raw);
        }

        long total = 0;
        long section = 0;
        int number = 0;
        for (int codePoint : raw.codePoints().toArray()) {
            int digit = chineseDigit(codePoint);
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = chineseUnit(codePoint);
            if (unit == 0) {
                return raw;
            }
            if (unit < 10000) {
                if (number == 0) {
                    number = 1;
                }
                section += (long) number * unit;
            } else {
                section += number;
                if (section == 0) {
                    section = 1;
                }
                total += section * unit;
                section = 0;
            }
            number = 0;
        }
        long result = total + section + number;
        return result > 0 ? Long.toString(result) : raw;
    }

    public static List<String> expandIntegerRange(
            String from,
            String to,
            int maxSize
    ) {
        try {
            int start = Integer.parseInt(canonicalArabic(from));
            int end = Integer.parseInt(canonicalArabic(to));
            if (end < start || end - start > maxSize) {
                return List.of(canonicalArabic(from));
            }
            List<String> result = new ArrayList<>(end - start + 1);
            for (int value = start; value <= end; value++) {
                result.add(Integer.toString(value));
            }
            return List.copyOf(result);
        } catch (NumberFormatException ex) {
            return List.of(canonicalArabic(from));
        }
    }

    private static int chineseDigit(int codePoint) {
        return switch (codePoint) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private static int chineseUnit(int codePoint) {
        return switch (codePoint) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1000;
            case '万' -> 10000;
            default -> 0;
        };
    }

    private static String toRoman(int value) {
        int[] values = {
                1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1
        };
        String[] numerals = {
                "M", "CM", "D", "CD", "C", "XC", "L", "XL",
                "X", "IX", "V", "IV", "I"
        };
        StringBuilder result = new StringBuilder();
        int remainder = value;
        for (int index = 0; index < values.length; index++) {
            while (remainder >= values[index]) {
                result.append(numerals[index]);
                remainder -= values[index];
            }
        }
        return result.toString();
    }
}
