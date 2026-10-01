package kz.alimbetov.akmai.rag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ResultFusion {

    private final RetrievalProperties properties;

    public ResultFusion(RetrievalProperties properties) {
        this.properties = properties;
    }

    public List<RetrievalHit> fuse(List<RetrievalHit> hits) {
        Map<String, Accumulator> accumulated = new LinkedHashMap<>();
        Map<String, Integer> ranks = new LinkedHashMap<>();

        for (RetrievalHit hit : hits) {
            int rank = ranks.merge(rankKey(hit), 1, Integer::sum);
            RetrievalEvidence evidence = new RetrievalEvidence(
                    hit.type(),
                    rank,
                    rawScore(hit)
            );
            accumulated.computeIfAbsent(key(hit), ignored -> new Accumulator(hit))
                    .add(evidence);
        }

        return accumulated.values().stream()
                .map(Accumulator::toHit)
                .sorted(Comparator.comparingDouble(RetrievalHit::fusedScore).reversed())
                .toList();
    }

    private String rankKey(RetrievalHit hit) {
        Object queryChunkId = hit.metadata().get("queryChunkId");
        return String.valueOf(queryChunkId) + "|" + hit.type();
    }

    private String key(RetrievalHit hit) {
        if (hit.chunkId() != null && !hit.chunkId().isBlank()) {
            return hit.chunkId();
        }
        return hit.type() + "|" + hit.documentId() + "|" + hit.text();
    }

    private Double rawScore(RetrievalHit hit) {
        Object score = hit.metadata().get("score");
        return score instanceof Number number ? number.doubleValue() : null;
    }

    private final class Accumulator {

        private final RetrievalHit representative;
        private final List<RetrievalEvidence> evidence = new ArrayList<>();
        private double fusedScore;

        private Accumulator(RetrievalHit representative) {
            this.representative = representative;
        }

        private void add(RetrievalEvidence item) {
            evidence.add(item);
            fusedScore += 1.0 / (properties.rrfK() + item.rank());
        }

        private RetrievalHit toHit() {
            return new RetrievalHit(
                    representative.type(),
                    representative.documentId(),
                    representative.chunkId(),
                    representative.text(),
                    representative.metadata(),
                    evidence,
                    fusedScore
            );
        }
    }
}
