package kz.alimbetov.akmai.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChunkIdentityTest {

    @Test
    void lengthPrefixedIdentitySeparatesTuplesThatCollidedWithNewlineEncoding() {
        ChunkIdentity identity = new ChunkIdentity();

        String first = identity.create(
                "a\n1",
                2,
                "s",
                "t"
        );
        String second = identity.create(
                "a",
                1,
                "2\ns",
                "t"
        );

        assertThat(first).isNotEqualTo(second);
    }
}
