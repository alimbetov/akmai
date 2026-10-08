package kz.alimbetov.akmai.knowledge.graph.dream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.config.SemanticMemoryProperties;
import kz.alimbetov.akmai.knowledge.graph.ChunkGraphNode;
import kz.alimbetov.akmai.knowledge.graph.GraphLifecycleGuard;
import kz.alimbetov.akmai.knowledge.graph.GraphNodeLockManager;
import kz.alimbetov.akmai.knowledge.graph.SemanticGraphStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class SemanticGraphPriorWriterTest {

    @Test
    void applyGateFailsClosedBeforeAnyMutationWork() {
        DreamRuntimeSwitches switches = mock(DreamRuntimeSwitches.class);
        when(switches.applyEnabled()).thenReturn(false);

        SemanticGraphPriorWriter writer = writer(
                switches,
                mock(AdaptiveGraphProperties.class)
        );

        assertThat(writer.applyCandidate(null, null, 0.95, Instant.now()))
                .isEqualTo(SemanticGraphPriorWriter.ApplyResult.APPLY_DISABLED);
    }

    @Test
    void graphVersionMismatchIsRejectedBeforeTransaction() {
        DreamRuntimeSwitches switches = mock(DreamRuntimeSwitches.class);
        when(switches.applyEnabled()).thenReturn(true);
        AdaptiveGraphProperties graphProperties = mock(AdaptiveGraphProperties.class);
        when(graphProperties.graphVersion()).thenReturn(2);

        SemanticGraphPriorWriter writer = writer(switches, graphProperties);
        DreamPair pair = DreamPair.of(
                new ChunkGraphNode(1, "a", 1, "c1"),
                new ChunkGraphNode(1, "b", 1, "c2")
        );
        DreamLeaseManager.Authority authority = new DreamLeaseManager.Authority(
                1,
                "a".repeat(64),
                "pod-a",
                7
        );

        assertThatThrownBy(() -> writer.applyCandidate(
                authority,
                pair,
                0.95,
                Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("graph version");
    }

    private SemanticGraphPriorWriter writer(
            DreamRuntimeSwitches switches,
            AdaptiveGraphProperties graphProperties
    ) {
        return new SemanticGraphPriorWriter(
                mock(JdbcTemplate.class),
                mock(PlatformTransactionManager.class),
                switches,
                graphProperties,
                new SemanticMemoryProperties(),
                mock(GraphNodeLockManager.class),
                mock(GraphLifecycleGuard.class),
                mock(SemanticGraphStateRepository.class),
                mock(DreamAuthorityGuard.class)
        );
    }
}
