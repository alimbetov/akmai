package kz.alimbetov.akmai.rag.retrieval;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Lightweight CRAG-style evidence assessment for telemetry only. It does not
 * trigger retries or alter answers, so the retrieval baseline remains stable.
 */
@Component
public class EvidenceQualityAssessor {

    private final RetrievalIntelligenceProperties properties;
    private final RetrievalObserver observer;

    public EvidenceQualityAssessor(
            RetrievalIntelligenceProperties properties,
            RetrievalObserver observer
    ) {
        this.properties = properties;
        this.observer = observer;
    }

    public EvidenceQuality assess(List<RetrievalHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return EvidenceQuality.WEAK;
        }
        if (hits.stream().anyMatch(this::exactAuthority)) {
            return EvidenceQuality.STRONG;
        }

        Set<RetrievalType> channels = EnumSet.noneOf(RetrievalType.class);
        Set<String> documents = new HashSet<>();
        for (RetrievalHit hit : hits) {
            if (hit == null) {
                continue;
            }
            if (hit.documentId() != null && !hit.documentId().isBlank()) {
                documents.add(hit.documentId());
            }
            addChannels(channels, hit);
        }

        if (hits.size() >= 2 && channels.size() >= 2 && documents.size() >= 2) {
            return EvidenceQuality.STRONG;
        }
        if (hits.size() >= 2 || channels.size() >= 2) {
            return EvidenceQuality.ADEQUATE;
        }
        return EvidenceQuality.WEAK;
    }

    public EvidenceQuality observe(List<RetrievalHit> hits) {
        EvidenceQuality quality = assess(hits);
        if (properties.evidenceQualityTelemetryEnabled()) {
            observer.evidenceQuality(quality.name());
        }
        return quality;
    }

    private boolean exactAuthority(RetrievalHit hit) {
        if (hit == null) {
            return false;
        }
        Object value = hit.metadata().get("authorityTier");
        return value instanceof Number number && number.intValue() == 0;
    }

    private void addChannels(
            Set<RetrievalType> channels,
            RetrievalHit hit
    ) {
        if (hit.evidence() != null && !hit.evidence().isEmpty()) {
            hit.evidence().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(RetrievalEvidence::type)
                    .filter(java.util.Objects::nonNull)
                    .forEach(channels::add);
            return;
        }
        if (hit.type() != null) {
            channels.add(hit.type());
        }
    }

    public enum EvidenceQuality {
        STRONG,
        ADEQUATE,
        WEAK
    }
}
