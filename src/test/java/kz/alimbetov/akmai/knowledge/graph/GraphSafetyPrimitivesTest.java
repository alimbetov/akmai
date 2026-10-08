package kz.alimbetov.akmai.knowledge.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import kz.alimbetov.akmai.util.TransactionTimeouts;
import org.junit.jupiter.api.Test;

class GraphSafetyPrimitivesTest {

    @Test
    void canonicalPairIsOrderIndependentAndAclSafe() {
        ChunkGraphNode a = new ChunkGraphNode(1, "a", 1, "c1");
        ChunkGraphNode b = new ChunkGraphNode(1, "b", 1, "c2");

        GraphPairCanonicalizer.CanonicalPair forward =
                GraphPairCanonicalizer.canonicalize(a, b);
        GraphPairCanonicalizer.CanonicalPair reverse =
                GraphPairCanonicalizer.canonicalize(b, a);

        assertThat(forward).isEqualTo(reverse);
        assertThat(forward.first()).isEqualTo(a);
        assertThatThrownBy(() -> GraphPairCanonicalizer.canonicalize(a, a))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("self");
        assertThatThrownBy(() -> GraphPairCanonicalizer.canonicalize(
                a,
                new ChunkGraphNode(2, "b", 1, "c2")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cross-ACL");
    }

    @Test
    void transactionTimeoutConversionIsPositiveAndBounded() {
        assertThat(TransactionTimeouts.seconds(Duration.ofMillis(1)))
                .isEqualTo(1);
        assertThat(TransactionTimeouts.seconds(Duration.ofSeconds(30)))
                .isEqualTo(30);
        assertThat(TransactionTimeouts.seconds(
                Duration.ofSeconds((long) Integer.MAX_VALUE + 100L)
        )).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> TransactionTimeouts.seconds(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
