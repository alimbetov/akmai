package kz.alimbetov.akmai.rag.retrieval;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kz.alimbetov.akmai.rag.query.QueryChunk;
import kz.alimbetov.akmai.rag.retrieval.plan.AdaptiveRetrievalPlanner;
import kz.alimbetov.akmai.rag.retrieval.plan.RetrievalPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MeasuredRetrievalCoordinator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MeasuredRetrievalCoordinator.class);

    private final AdaptiveRetrievalPlanner adaptiveRetrievalPlanner;
    private final RetrievalObserver observer;

    public MeasuredRetrievalCoordinator(
            AdaptiveRetrievalPlanner adaptiveRetrievalPlanner,
            RetrievalObserver observer
    ) {
        this.adaptiveRetrievalPlanner = adaptiveRetrievalPlanner;
        this.observer = observer;
    }

    public void observePlan(
            List<QueryChunk> chunks,
            RetrievalPlan currentPlan
    ) {
        try {
            AdaptiveRetrievalPlanner.ShadowPlanReport report =
                    adaptiveRetrievalPlanner.shadow(chunks, currentPlan);
            if (!report.enabled()) {
                return;
            }
            for (AdaptiveRetrievalPlanner.ChunkRecommendation recommendation
                    : report.recommendations()) {
                observer.plannerQueryClass(
                        recommendation.queryClass().name()
                );
                recommendation.currentLanes().forEach(type ->
                        observer.plannerLane("current", type, 1)
                );
                recommendation.recommendedLanes().forEach(type ->
                        observer.plannerLane("shadow", type, 1)
                );

                EnumSet<RetrievalType> omitted = copyOf(
                        recommendation.currentLanes()
                );
                omitted.removeAll(recommendation.recommendedLanes());
                omitted.forEach(type ->
                        observer.plannerDelta("omitted", type, 1)
                );

                EnumSet<RetrievalType> added = copyOf(
                        recommendation.recommendedLanes()
                );
                added.removeAll(recommendation.currentLanes());
                added.forEach(type ->
                        observer.plannerDelta("added", type, 1)
                );
            }
        } catch (RuntimeException exception) {
            LOGGER.debug(
                    "measured_retrieval event=planner_shadow_failed errorType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    public void recordStage(
            RetrievalAttributionStage stage,
            List<RetrievalHit> hits
    ) {
        if (stage == null || hits == null || hits.isEmpty()) {
            return;
        }
        try {
            EnumMap<RetrievalType, Integer> counts =
                    new EnumMap<>(RetrievalType.class);
            for (RetrievalHit hit : hits) {
                addChannels(counts, hit);
            }
            publish(stage, counts);
        } catch (RuntimeException exception) {
            LOGGER.debug(
                    "measured_retrieval event=attribution_failed stage={} errorType={}",
                    stage,
                    exception.getClass().getSimpleName()
            );
        }
    }

    public void recordCitations(
            List<RetrievalHit> context,
            CitationValidator.CitationValidation validation
    ) {
        recordSources(
                RetrievalAttributionStage.CITED,
                context,
                validation
        );
    }

    public void recordGrounded(
            List<RetrievalHit> context,
            CitationValidator.CitationValidation validation
    ) {
        recordSources(
                RetrievalAttributionStage.GROUNDED,
                context,
                validation
        );
    }

    private void recordSources(
            RetrievalAttributionStage stage,
            List<RetrievalHit> context,
            CitationValidator.CitationValidation validation
    ) {
        if (context == null
                || context.isEmpty()
                || validation == null
                || validation.citedSources() == null
                || validation.citedSources().isEmpty()) {
            return;
        }
        try {
            EnumMap<RetrievalType, Integer> counts =
                    new EnumMap<>(RetrievalType.class);
            for (SourceRef source : validation.citedSources()) {
                int index = source.number() - 1;
                if (index < 0 || index >= context.size()) {
                    continue;
                }
                addChannels(counts, context.get(index));
            }
            publish(stage, counts);
        } catch (RuntimeException exception) {
            LOGGER.debug(
                    "measured_retrieval event=source_attribution_failed stage={} errorType={}",
                    stage,
                    exception.getClass().getSimpleName()
            );
        }
    }

    private void addChannels(
            Map<RetrievalType, Integer> counts,
            RetrievalHit hit
    ) {
        if (hit == null) {
            return;
        }
        channels(hit).forEach(type -> counts.merge(type, 1, Integer::sum));
    }

    private void publish(
            RetrievalAttributionStage stage,
            Map<RetrievalType, Integer> counts
    ) {
        counts.forEach((type, count) -> {
            observer.attribution(stage, type, count);
            observer.attributionRequest(stage, type);
        });
    }

    private Set<RetrievalType> channels(RetrievalHit hit) {
        if (hit.type() == RetrievalType.GRAPH) {
            return Set.of(RetrievalType.GRAPH);
        }
        if (hit.evidence() == null || hit.evidence().isEmpty()) {
            return hit.type() == null ? Set.of() : Set.of(hit.type());
        }

        EnumSet<RetrievalType> channels =
                EnumSet.noneOf(RetrievalType.class);
        hit.evidence().stream()
                .filter(java.util.Objects::nonNull)
                .map(RetrievalEvidence::type)
                .filter(java.util.Objects::nonNull)
                .forEach(channels::add);
        if (channels.isEmpty() && hit.type() != null) {
            channels.add(hit.type());
        }
        return Set.copyOf(channels);
    }

    private EnumSet<RetrievalType> copyOf(Set<RetrievalType> values) {
        EnumSet<RetrievalType> result =
                EnumSet.noneOf(RetrievalType.class);
        if (values != null) {
            result.addAll(values);
        }
        return result;
    }
}
