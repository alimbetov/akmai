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
     * Dream is enabled only when both the immutable application configuration
     * and the authoritative runtime database switch allow execution. The
     * static flag is the outer safety gate; the runtime flag is an operational
     * kill switch and therefore must not be authorized by stale cache state.
     */
    public boolean enabled() {
        return properties.dreamEnabled()
                && appParameters.isEnabledAuthoritative(
                        AppParameterKey.ADAPTIVE_GRAPH_DREAM_ENABLED
                );
    }

    /**
     * DREAM-5 apply is independently doubly gated. Runtime enablement can never
     * override a statically disabled apply policy, and apply authorization is
     * always read from authoritative database state.
     */
    public boolean applyEnabled() {
        if (!enabled() || !properties.dream().applyEnabled()) {
            return false;
        }
        return appParameters.isEnabledAuthoritative(
                AppParameterKey.ADAPTIVE_GRAPH_DREAM_APPLY_ENABLED
        );
    }

    public AdaptiveGraphProperties.Dream staticPolicy() {
        return properties.dream();
    }
}
