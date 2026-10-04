package kz.alimbetov.akmai.knowledge.semantic;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

final class SlavicTurkicTextNormalizer {

    private SlavicTurkicTextNormalizer() {
    }

    static List<String> tokens(String text) {
        String normalized = Normalizer.normalize(
                        text == null ? "" : text,
                        Normalizer.Form.NFC
                )
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return List.of();
        }
        return List.of(normalized.split("\\s+"));
    }
}
