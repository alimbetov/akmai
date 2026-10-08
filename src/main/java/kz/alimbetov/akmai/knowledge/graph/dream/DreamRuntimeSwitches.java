package kz.alimbetov.akmai.knowledge.graph.dream;

import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.runtimeconfig.AppParameterKey;
import kz.alimbetov.akmai.runtimeconfig.AppParameterService;
import org.springframework.stereotype.Component;

/** Single static/runtime safety-gate boundary used by scheduler/coordinator. */
@Component
public class DreamRuntimeSwitches {

    private final AdaptiveGraphProperties properties;
    private final AppParameterService appParameters;

    public DreamRuntimeSwitches(
            AdaptiveGraphProperties properties,
            AppParameterService appParameters
    ) {
        this.properties = properties;
        this.appParameters = appParameters;
    }

    /**
     * DREAM v1 is enabled only when both the immutable application configuration
     * and the runtime database switch allow execution. The static flag is the
     * outer safety gate; the runtime flag is an operational kill switch.
     */
    public boolean enabled() {
        return properties.dreamEnabled()
                && appParameters.isEnabled(
                        AppParameterKey.ADAPTIVE_GRAPH_DREAM_ENABLED
                );
    }

    /**
     * Apply mode remains doubly gated as well. DREAM-1..4B does not implement
     * graph apply, but keeping the gate explicit prevents a runtime flag from
     * overriding a statically disabled policy in later milestones.
     */
    public boolean applyEnabled() {
        if (!enabled() || !properties.dream().applyEnabled()) {
            return false;
        }
        return appParameters.isEnabled(
                AppParameterKey.ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED
        );
    }

    public AdaptiveGraphProperties.Dream staticPolicy() {
        return properties.dream();
    }
}
