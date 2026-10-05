package kz.alimbetov.akmai.rag.retrieval;

import java.util.List;
import java.util.Set;

/**
 * Canonical synchronous lifecycle fence for retrieval-visible generations.
 *
 * <p>The retention scheduler is cleanup machinery, not an authorization or
 * visibility boundary. Implementations must therefore reject expired rows at
 * read time even while their lifecycle row is still ACTIVE.</p>
 */
public interface PublishedLifecycleEligibility {

    List<RetrievalHit> filter(
            List<RetrievalHit> hits,
            Set<Long> accessLevels
    );

    static PublishedLifecycleEligibility allowAll() {
        return (hits, accessLevels) -> hits == null ? List.of() : List.copyOf(hits);
    }
}
