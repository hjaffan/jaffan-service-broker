package com.jaffan.broker.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaffan.broker.catalog.CatalogIds;
import com.jaffan.broker.naming.PasswordGenerator;
import com.jaffan.broker.provision.MariaDbProvisioner;
import com.jaffan.broker.provision.PostgresProvisioner;
import com.jaffan.broker.provision.Provisioner;
import com.jaffan.broker.service.BackendRouter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BackendRoutingTest {

    // Backends with null data sources: routing is pure metadata, no connection needed here.
    private final Backend pgDev = new Backend("pg-dev", DatabaseEngine.POSTGRES, "pg-dev.host", 5432,
            null, "admin", "secret", null);
    private final Backend pgProd = new Backend("pg-prod", DatabaseEngine.POSTGRES, "pg-prod.host", 5432,
            null, "admin", "secret", null);
    private final Backend mariaDev = new Backend("maria-dev", DatabaseEngine.MARIADB, "maria-dev.host",
            3306, null, "root", "secret", null);
    private final Backend mariaProd = new Backend("maria-prod", DatabaseEngine.MARIADB, "maria-prod.host",
            3306, null, "root", "secret", null);

    private final BackendRegistry registry = new BackendRegistry(
            Map.of(
                    CatalogIds.POSTGRES_DEV_PLAN_ID, pgDev,
                    CatalogIds.POSTGRES_PROD_PLAN_ID, pgProd,
                    CatalogIds.MARIADB_DEV_PLAN_ID, mariaDev,
                    CatalogIds.MARIADB_PROD_PLAN_ID, mariaProd),
            Map.of(
                    CatalogIds.POSTGRES_DEV_PLAN_ID, List.of(CatalogIds.POSTGRES_PROD_PLAN_ID),
                    CatalogIds.POSTGRES_PROD_PLAN_ID, List.of(CatalogIds.POSTGRES_DEV_PLAN_ID),
                    CatalogIds.MARIADB_DEV_PLAN_ID, List.of(CatalogIds.MARIADB_PROD_PLAN_ID),
                    CatalogIds.MARIADB_PROD_PLAN_ID, List.of(CatalogIds.MARIADB_DEV_PLAN_ID)));

    @Test
    void eachPlanRoutesToItsBackendServer() {
        assertThat(registry.forPlan(CatalogIds.POSTGRES_DEV_PLAN_ID).host()).isEqualTo("pg-dev.host");
        assertThat(registry.forPlan(CatalogIds.POSTGRES_PROD_PLAN_ID).host()).isEqualTo("pg-prod.host");
        assertThat(registry.forPlan(CatalogIds.MARIADB_DEV_PLAN_ID).host()).isEqualTo("maria-dev.host");
        assertThat(registry.forPlan(CatalogIds.MARIADB_PROD_PLAN_ID).host()).isEqualTo("maria-prod.host");
    }

    @Test
    void postgresPlansRouteToPostgresEngineAndMariaToMaria() {
        assertThat(registry.forPlan(CatalogIds.POSTGRES_DEV_PLAN_ID).engine())
                .isEqualTo(DatabaseEngine.POSTGRES);
        assertThat(registry.forPlan(CatalogIds.MARIADB_PROD_PLAN_ID).engine())
                .isEqualTo(DatabaseEngine.MARIADB);
    }

    @Test
    void siblingOfADevPlanIsTheProdPlanOnTheSameEngine() {
        assertThat(registry.siblingBackends(CatalogIds.POSTGRES_DEV_PLAN_ID))
                .containsExactly(pgProd);
        assertThat(registry.siblingBackends(CatalogIds.MARIADB_PROD_PLAN_ID))
                .containsExactly(mariaDev);
    }

    @Test
    void unknownPlanIsRejected() {
        assertThatThrownBy(() -> registry.forPlan("not-a-real-plan"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routerSelectsProvisionerMatchingTheBackendEngine() {
        PasswordGenerator passwords = new PasswordGenerator();
        List<Provisioner> provisioners =
                List.of(new PostgresProvisioner(passwords), new MariaDbProvisioner(passwords));
        BackendRouter router = new BackendRouter(registry, provisioners);

        assertThat(router.provisionerFor(pgDev).engine()).isEqualTo(DatabaseEngine.POSTGRES);
        assertThat(router.provisionerFor(mariaProd).engine()).isEqualTo(DatabaseEngine.MARIADB);
        assertThat(router.backendFor(CatalogIds.MARIADB_DEV_PLAN_ID)).isEqualTo(mariaDev);
    }
}
