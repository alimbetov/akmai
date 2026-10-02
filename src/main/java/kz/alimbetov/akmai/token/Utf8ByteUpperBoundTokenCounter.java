package kz.alimbetov.akmai.token;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class Utf8ByteUpperBoundTokenCounter implements TokenUpperBoundCounter {

    @Override
    public int upperBound(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // Conservative tokenizer-independent bound: a token cannot encode
        // less than one byte of the UTF-8 input, so token count cannot exceed
        // the number of input bytes.
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public String profile() {
        return "utf8-byte-upper-bound-v1";
    }
}
