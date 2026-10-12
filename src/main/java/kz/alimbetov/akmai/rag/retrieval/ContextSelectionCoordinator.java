package kz.alimbetov.akmai.rag.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Applies lifecycle eligibility before context budgeting and performs at most
 * one deterministic backfill round when the final TOCTOU fence removes a
 * selected hit.
 */
@Component
public class ContextSelectionCoordinator {

    static final int MAX_CONTEXT_SELECTION_ROUNDS = 2;

    private final PublishedLifecycleEligibility lifecycleEligibility;
    private final ContextDiversityFilter diversityFilter;
    private final ContextBudget contextBudget;
    private final PublishedContextRevalidator contextRevalidator;

    public ContextSelectionCoordinator(
            PublishedLifecycleEligibility lifecycleEligibility,
            ContextDiversityFilter diversityFilter,
            ContextBudget contextBudget,
            PublishedContextRevalidator contextRevalidator
    ) {
        this.lifecycleEligibility = lifecycleEligibility;
        this.diversityFilter = diversityFilter;
        this.contextBudget = contextBudget;
        this.contextRevalidator = contextRevalidator;
    }

    public List<RetrievalHit> select(
            List<RetrievalHit> candidates,
            String question,
            Set<Long> accessLevels
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RetrievalHit> eligiblePool = lifecycleEligibility.filter(
                candidates,
                accessLevels
        );
        if (eligiblePool.isEmpty()) {
            return List.of();
        }

        List<RetrievalHit> firstSelection = selectWithinBudget(
                eligiblePool,
                question
        );
        List<RetrievalHit> firstValidated = contextRevalidator.revalidate(
                firstSelection,
                accessLevels
        );
        if (sameIdentityOrder(firstSelection, firstValidated)) {
            return firstValidated;
        }

        Set<Key> rejected = new LinkedHashSet<>(keys(firstSelection));
        rejected.removeAll(keys(firstValidated));
        if (rejected.isEmpty()) {
            return firstValidated;
        }

        List<RetrievalHit> backfillPool = eligiblePool.stream()
                .filter(hit -> !rejected.contains(Key.of(hit)))
                .toList();
        List<RetrievalHit> secondSelection = selectWithinBudget(
                backfillPool,
                question
        );

        // Round two always revalidates the complete rebuilt selection. A hit
        // that survived round one may itself become stale while backfill runs.
        // There is intentionally no third round.
        return contextRevalidator.revalidate(secondSelection, accessLevels);
    }

    private List<RetrievalHit> selectWithinBudget(
            List<RetrievalHit> candidates,
            String question
    ) {
        List<RetrievalHit> diversified = diversityFilter.apply(candidates);
        return contextBudget.apply(diversified, question);
    }

    private boolean sameIdentityOrder(
            List<RetrievalHit> left,
            List<RetrievalHit> right
    ) {
        return keys(left).equals(keys(right));
    }

    private List<Key> keys(List<RetrievalHit> hits) {
        return hits.stream().map(Key::of).toList();
    }

    private record Key(
            long accessLevel,
            String documentId,
            long generation,
            String chunkId
    ) {
        static Key of(RetrievalHit hit) {
            return new Key(
                    hit.accessLevel(),
                    hit.documentId(),
                    hit.generation(),
                    hit.chunkId()
            );
        }
    }
}
