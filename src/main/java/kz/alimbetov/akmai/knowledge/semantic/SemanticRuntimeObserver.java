package kz.alimbetov.akmai.knowledge.semantic;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class SemanticRuntimeObserver {

    private final MeterRegistry meterRegistry;

    public SemanticRuntimeObserver(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void matcher(
            Duration duration,
            int totalSurfaces,
            int candidateSurfaces,
            int matches
    ) {
        Timer.builder("akmai.semantic.matcher")
                .register(meterRegistry)
                .record(duration);
        meterRegistry.summary("akmai.semantic.matcher.surfaces.total")
                .record(totalSurfaces);
        meterRegistry.summary("akmai.semantic.matcher.surfaces.candidates")
                .record(candidateSurfaces);
        meterRegistry.summary("akmai.semantic.matcher.matches")
                .record(matches);
    }

    public void queryAnalysis(int domains, int concepts) {
        meterRegistry.summary("akmai.semantic.query.domains")
                .record(domains);
        meterRegistry.summary("akmai.semantic.query.concepts")
                .record(concepts);
    }

    public void chunkAnnotation(int domains, int concepts) {
        meterRegistry.summary("akmai.semantic.chunk.domains")
                .record(domains);
        meterRegistry.summary("akmai.semantic.chunk.concepts")
                .record(concepts);
    }
}
