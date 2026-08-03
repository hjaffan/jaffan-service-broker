package com.jaffan.broker.provision;

import com.jaffan.broker.backend.Backend;
import com.jaffan.broker.backend.DatabaseEngine;
import java.util.List;
import java.util.Map;

/**
 * Engine-specific database operations behind the OSB verbs. One implementation per
 * {@link DatabaseEngine}; the service layer picks the right one off the target backend's engine.
 *
 * <p>Every method is idempotent-friendly and expressed purely in terms of GUIDs, so the broker holds
 * no state of its own. Implementations must never log passwords or credential material.
 */
public interface Provisioner {

    DatabaseEngine engine();

    /**
     * Create the tenant database (and, for Postgres, its NOLOGIN owner role) on the backend. Optional
     * whitelisted extensions are applied inside the tenant DB; ignored by engines that have no
     * equivalent. Callers guarantee the database does not already exist on this backend.
     */
    void provision(Backend backend, String instanceGuid, List<String> extensions);

    /** True if the tenant database for this instance exists on the given backend. */
    boolean databaseExists(Backend backend, String instanceGuid);

    /**
     * Create a login role/user for the binding and return the credentials map. For Postgres the login
     * role is set up so every object the app creates ends up owned by the instance role (see impl).
     */
    Map<String, Object> bind(Backend backend, String instanceGuid, String bindingGuid);

    /** True if the binding's login role/user already exists on the backend. */
    boolean bindingExists(Backend backend, String bindingGuid);

    /** Terminate the binding's live sessions, then drop its login role/user. Idempotent. */
    void unbind(Backend backend, String instanceGuid, String bindingGuid);

    /** Dispose of the tenant database per the given mode. Callers guarantee the database exists. */
    void deprovision(Backend backend, String instanceGuid, DeprovisionMode mode);
}
