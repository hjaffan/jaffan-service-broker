package com.jaffan.broker.backend;

import java.util.List;
import java.util.Map;

/**
 * Routes an OSB {@code plan_id} to the physical {@link Backend} that plan lives on — this is the whole
 * of "which server does this instance belong to". Because a plan id deterministically maps to a
 * backend, the broker never has to remember where an instance was placed.
 *
 * <p>It also answers "what are the <em>sibling</em> backends for this plan" (the other plans of the
 * same service). Provision uses that to detect a same-GUID/different-plan collision: if the tenant
 * database already exists on a sibling backend, the caller asked to re-provision the same instance
 * under a different plan, which must be a 409 rather than a silent no-op.
 */
public final class BackendRegistry {

    private final Map<String, Backend> byPlanId;
    private final Map<String, List<String>> siblingPlansByPlanId;

    /**
     * @param byPlanId              plan_id -> backend (every catalog plan must be present)
     * @param siblingPlansByPlanId  plan_id -> other plan_ids of the same service (may be empty lists)
     */
    public BackendRegistry(Map<String, Backend> byPlanId, Map<String, List<String>> siblingPlansByPlanId) {
        this.byPlanId = Map.copyOf(byPlanId);
        this.siblingPlansByPlanId = Map.copyOf(siblingPlansByPlanId);
    }

    /** Backend for a plan, or an {@link IllegalArgumentException} if the plan id is unknown. */
    public Backend forPlan(String planId) {
        Backend backend = byPlanId.get(planId);
        if (backend == null) {
            throw new IllegalArgumentException("no backend configured for plan id: " + planId);
        }
        return backend;
    }

    /** Backends for the other plans of the same service (used for collision detection on provision). */
    public List<Backend> siblingBackends(String planId) {
        return siblingPlansByPlanId.getOrDefault(planId, List.of()).stream()
                .map(byPlanId::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public boolean knowsPlan(String planId) {
        return byPlanId.containsKey(planId);
    }

    /** All distinct backends, for the startup validation sweep. */
    public List<Backend> allBackends() {
        return byPlanId.values().stream().distinct().toList();
    }
}
