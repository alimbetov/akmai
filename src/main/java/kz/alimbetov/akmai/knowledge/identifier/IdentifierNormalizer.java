package kz.alimbetov.akmai.knowledge.identifier;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class IdentifierNormalizer {

    public String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toUpperCase(Locale.ROOT)
                .replace("№", "")
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
