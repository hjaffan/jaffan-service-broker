package com.jaffan.broker.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaffan.broker.catalog.CatalogIds;
import com.jaffan.broker.naming.PasswordGenerator;
import com.jaffan.broker.provision.PostgresProvisioner;
import com.jaffan.broker.service.BackendRouter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BackendRoutingTest {

    // Backend with a null data source: routing is pure metadata, no connection needed here.
    private final Backend postgres = new Backend("postgres-ha", DatabaseEngine.POSTGRES,
            List.of(new HostPort("pg-ha-0.host", 5432), new HostPort("pg-ha-1.host", 5432)),
            null, "admin", "secret", null);

    private final BackendRegistry registry =
            new BackendRegistry(Map.of(CatalogIds.POSTGRES_SHARED_PLAN_ID, postgres));

    @Test
    void theSharedPlanRoutesToThePostgresHaCluster() {
        assertThat(registry.forPlan(CatalogIds.POSTGRES_SHARED_PLAN_ID)).isEqualTo(postgres);
        assertThat(registry.forPlan(CatalogIds.POSTGRES_SHARED_PLAN_ID).engine())
                .isEqualTo(DatabaseEngine.POSTGRES);
        assertThat(registry.forPlan(CatalogIds.POSTGRES_SHARED_PLAN_ID).hostSpec())
                .isEqualTo("pg-ha-0.host:5432,pg-ha-1.host:5432");
    }

    @Test
    void unknownPlanIsRejected() {
        assertThatThrownBy(() -> registry.forPlan("not-a-real-plan"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routerSelectsTheProvisionerMatchingTheBackendEngine() {
        BackendRouter router = new BackendRouter(registry,
                List.of(new PostgresProvisioner(new PasswordGenerator())));

        assertThat(router.provisionerFor(postgres).engine()).isEqualTo(DatabaseEngine.POSTGRES);
        assertThat(router.backendFor(CatalogIds.POSTGRES_SHARED_PLAN_ID)).isEqualTo(postgres);
    }

    @Test
    void externalHostsDefaultToTheBrokerFacingHosts() {
        assertThat(postgres.externalHosts()).isEqualTo(postgres.hosts());

        Backend withExternal = new Backend("postgres-ha", DatabaseEngine.POSTGRES,
                List.of(new HostPort("internal.host", 5432)),
                List.of(new HostPort("apps.host", 6432)), "admin", "secret", null);
        assertThat(withExternal.externalHosts()).containsExactly(new HostPort("apps.host", 6432));
    }
}
