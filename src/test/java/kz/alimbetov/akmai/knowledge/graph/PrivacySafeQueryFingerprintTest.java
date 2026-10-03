package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import org.junit.jupiter.api.Test;

class PrivacySafeQueryFingerprintTest {

    @Test
    void fingerprintIsDeterministicAndBoundedWithoutPersistingQuery() {
        AdaptiveGraphProperties properties = new AdaptiveGraphProperties(
                true,
                false,
                false,
                false,
                1,
                new AdaptiveGraphProperties.Learning(
                        8,
                        32,
                        "0123456789abcdef0123456789abcdef"
                ),
                AdaptiveGraphTestProperties.create(
                        new AdaptiveGraphProperties.BandQuotas(8, 8, 16)
                ).scoring(),
                AdaptiveGraphTestProperties.create(
                        new AdaptiveGraphProperties.BandQuotas(8, 8, 16)
                ).maintenance(),
                new AdaptiveGraphProperties.BandQuotas(8, 8, 16),
                new AdaptiveGraphProperties.Storage(32)
        );
        PrivacySafeQueryFingerprint fingerprint =
                new PrivacySafeQueryFingerprint(properties);
        List<QueryChunk> query = List.of(new QueryChunk(
                "q",
                0,
                "Sensitive raw text",
                "normalized text",
                "semantic text",
                "en",
                List.of()
        ));

        int first = fingerprint.bucket(query);
        int second = fingerprint.bucket(query);

        assertThat(first).isEqualTo(second);
        assertThat(first).isBetween(0, 255);
    }
}
