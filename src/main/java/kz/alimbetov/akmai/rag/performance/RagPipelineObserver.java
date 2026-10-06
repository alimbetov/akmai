package kz.alimbetov.akmai.rag.performance;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RagPipelineObserver {

    private final Map<RagPipelineStage, Timer> timers =
            new EnumMap<>(RagPipelineStage.class);

    public RagPipelineObserver(MeterRegistry meterRegistry) {
        for (RagPipelineStage stage : RagPipelineStage.values()) {
            timers.put(
                    stage,
                    Timer.builder("akmai.rag.stage")
                            .description("AkmAI RAG pipeline stage latency")
                            .tag("stage", stage.name().toLowerCase(java.util.Locale.ROOT))
                            .publishPercentiles(0.50, 0.95, 0.99)
                            .publishPercentileHistogram()
                            .register(meterRegistry)
            );
        }
    }

    public void record(RagPipelineStage stage, long elapsedNanos) {
        if (stage == null || elapsedNanos < 0) {
            return;
        }
        Timer timer = timers.get(stage);
        if (timer != null) {
            timer.record(Duration.ofNanos(elapsedNanos));
        }
    }
}
