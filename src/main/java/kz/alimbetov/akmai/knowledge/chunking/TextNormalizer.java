package kz.alimbetov.akmai.knowledge.chunking;

import org.springframework.stereotype.Component;

@Component
public class TextNormalizer {

    public String normalize(String text) {
        if (text == null) {
            return "";
        }

        return text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }
}
