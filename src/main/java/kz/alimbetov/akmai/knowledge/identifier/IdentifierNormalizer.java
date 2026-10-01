package kz.alimbetov.akmai.knowledge.identifier;

import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class IdentifierNormalizer {

    private static final int MAX_IDENTIFIER_LENGTH = 500;

    public String normalize(String value) {
        return normalize(null, value);
    }

    public String normalize(IdentifierType type, String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
                .trim()
                .toUpperCase(Locale.ROOT)
                .replace('№', '#')
                .replaceAll("\\s+", "");

        if (normalized.length() > MAX_IDENTIFIER_LENGTH) {
            return "";
        }

        // Keep semantically significant separators. This intentionally keeps
        // AB-12 distinct from AB/12 (D35).
        normalized = normalized.replaceAll("[^\\p{L}\\p{N}./_#-]", "");
        return normalized;
    }

    public int maxLength() {
        return MAX_IDENTIFIER_LENGTH;
    }
}
