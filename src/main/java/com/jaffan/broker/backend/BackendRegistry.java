package com.jaffan.broker.backend;

import java.util.List;
import java.util.Map;

/**
 * Routes an OSB {@code plan_id} to the physical {@link Backend} that plan lives on — this is the
 * whole of "which cluster does this instance belong to". Because a plan id deterministically maps
 * to a backend, the broker never has to remember where an instance was placed.
 */
public final class BackendRegistry {

    private final Map<String, Backend> byPlanId;

    /** @param byPlanId plan_id -> backend (every catalog plan must be present) */
    public BackendRegistry(Map<String, Backend> byPlanId) {
        this.byPlanId = Map.copyOf(byPlanId);
    }

    /** Backend for a plan, or an {@link IllegalArgumentException} if the plan id is unknown. */
    public Backend forPlan(String planId) {
        Backend backend = byPlanId.get(planId);
        if (backend == null) {
            throw new IllegalArgumentException("no backend configured for plan id: " + planId);
        }
        return backend;
    }

    public boolean knowsPlan(String planId) {
        return byPlanId.containsKey(planId);
    }

    /** All distinct backends, for the startup validation sweep. */
    public List<Backend> allBackends() {
        return byPlanId.values().stream().distinct().toList();
    }
}
