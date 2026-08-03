package com.jaffan.broker.service;

import com.jaffan.broker.backend.Backend;
import com.jaffan.broker.backend.BackendRegistry;
import com.jaffan.broker.backend.DatabaseEngine;
import com.jaffan.broker.provision.Provisioner;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Thin front door the service layer uses to resolve, from an OSB {@code plan_id}, both the physical
 * {@link Backend} and the {@link Provisioner} that knows how to talk to that backend's engine. Keeps
 * the registry + provisioner-by-engine lookups in one place so the two service classes stay focused on
 * OSB semantics.
 */
@Component
public class BackendRouter {

    private final BackendRegistry registry;
    private final Map<DatabaseEngine, Provisioner> provisionersByEngine;

    public BackendRouter(BackendRegistry registry, List<Provisioner> provisioners) {
        this.registry = registry;
        this.provisionersByEngine = new EnumMap<>(DatabaseEngine.class);
        for (Provisioner provisioner : provisioners) {
            this.provisionersByEngine.put(provisioner.engine(), provisioner);
        }
    }

    public Backend backendFor(String planId) {
        return registry.forPlan(planId);
    }

    public List<Backend> siblingBackendsFor(String planId) {
        return registry.siblingBackends(planId);
    }

    public Provisioner provisionerFor(Backend backend) {
        Provisioner provisioner = provisionersByEngine.get(backend.engine());
        if (provisioner == null) {
            throw new IllegalStateException("no provisioner registered for engine " + backend.engine());
        }
        return provisioner;
    }
}
