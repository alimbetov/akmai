package kz.alimbetov.akmai.knowledge.chunking;

import java.text.Normalizer;
import org.springframework.stereotype.Component;

@Component
public class TextNormalizer {

    public String normalize(String text) {
        if (text == null) {
            return "";
        }

        return Normalizer.normalize(text, Normalizer.Form.NFC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }
}
