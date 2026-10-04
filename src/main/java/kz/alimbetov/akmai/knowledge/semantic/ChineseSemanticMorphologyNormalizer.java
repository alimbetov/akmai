package kz.alimbetov.akmai.knowledge.semantic;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ChineseSemanticMorphologyNormalizer
        implements SemanticMorphologyNormalizer {

    @Override
    public String language() {
        return "zh";
    }

    @Override
    public List<String> normalizeTokens(String text) {
        String value = Normalizer.normalize(
                        text == null ? "" : text,
                        Normalizer.Form.NFC
                )
                .toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        StringBuilder nonHan = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
            if (isHan(codePoint)) {
                flush(nonHan, tokens);
                tokens.add(new String(
                        Character.toChars(codePoint)
                ));
            } else if (Character.isLetterOrDigit(codePoint)) {
                nonHan.appendCodePoint(codePoint);
            } else {
                flush(nonHan, tokens);
            }
        });
        flush(nonHan, tokens);
        return List.copyOf(tokens);
    }

    @Override
    public List<String> lemmaTokens(String text) {
        return normalizeTokens(text);
    }

    @Override
    public List<String> stemTokens(String text) {
        return normalizeTokens(text);
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN;
    }

    private void flush(
            StringBuilder buffer,
            List<String> tokens
    ) {
        if (!buffer.isEmpty()) {
            tokens.add(buffer.toString());
            buffer.setLength(0);
        }
    }
}
