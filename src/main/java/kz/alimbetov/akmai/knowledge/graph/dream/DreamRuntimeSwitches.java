package kz.alimbetov.akmai.knowledge.graph.dream;

import kz.alimbetov.akmai.config.AdaptiveGraphProperties;
import kz.alimbetov.akmai.runtimeconfig.AppParameterKey;
import kz.alimbetov.akmai.runtimeconfig.AppParameterService;
import org.springframework.stereotype.Component;

/** Single runtime-switch boundary used by scheduler/coordinator. */
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

    public boolean enabled() {
        return appParameters.isEnabled(AppParameterKey.ADAPTIVE_GRAPH_DREAM_ENABLED);
    }

    public boolean applyEnabled() {
        if (!enabled()) {
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
