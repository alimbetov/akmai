package kz.alimbetov.akmai.knowledge.chunking;

import java.util.Map;

public record IndustryCode(
        String id,
        IndustryDomain domain,
        Map<String, String> names
) {

    public IndustryCode {
        if (id == null
                || !id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException(
                    "industry id must be a lowercase stable identifier"
            );
        }
        if (domain == null) {
            throw new IllegalArgumentException(
                    "industry domain must not be null"
            );
        }
        names = Map.copyOf(names == null ? Map.of() : names);
    }
}
