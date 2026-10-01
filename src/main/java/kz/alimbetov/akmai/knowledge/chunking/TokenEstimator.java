package kz.alimbetov.akmai.knowledge.chunking;

import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {

    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        int characters = text.length();

        // Conservative multilingual heuristic suitable for KK/RU/EN/ZH pre-chunking.
        return Math.max(1, (int) Math.ceil(characters / 3.2));
    }
}
