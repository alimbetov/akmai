package kz.alimbetov.akmai.rag.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import kz.alimbetov.akmai.rag.api.QuestionRequest;
import org.junit.jupiter.api.Test;

class RequestAccessLevelResolverTest {

    private final RequestAccessLevelResolver resolver =
            new RequestAccessLevelResolver();

    @Test
    void returnsNormalizedRequestedLevels() {
        assertThat(resolver.resolve(new QuestionRequest(
                "question",
                Set.of(3L, 1L, 2L)
        ))).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void emptyScopeFailsClosed() {
        assertThat(resolver.resolve(new QuestionRequest(
                "question",
                Set.of()
        ))).isEmpty();
        assertThat(resolver.resolve(new QuestionRequest(
                "question",
                null
        ))).isEmpty();
    }

    @Test
    void directInvalidScopeIsRejected() {
        HashSet<Long> values = new HashSet<>();
        values.add(1L);
        values.add(0L);

        assertThatThrownBy(() -> resolver.resolve(
                new QuestionRequest("question", values)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
