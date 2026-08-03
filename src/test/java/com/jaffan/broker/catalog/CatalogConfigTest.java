package com.jaffan.broker.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.servicebroker.model.catalog.Catalog;
import org.springframework.cloud.servicebroker.model.catalog.Plan;
import org.springframework.cloud.servicebroker.model.catalog.ServiceDefinition;

class CatalogConfigTest {

    private final Catalog catalog = new CatalogConfig().catalog();

    @Test
    void exposesTwoServicesWithTheFixedIds() {
        assertThat(catalog.getServiceDefinitions())
                .extracting(ServiceDefinition::getId)
                .containsExactlyInAnyOrder(CatalogIds.POSTGRES_SERVICE_ID, CatalogIds.MARIADB_SERVICE_ID);
        assertThat(catalog.getServiceDefinitions())
                .extracting(ServiceDefinition::getName)
                .containsExactlyInAnyOrder("postgres", "mariadb");
    }

    @Test
    void everyPlanIsBindableFreeAndNotUpdateableWithFixedIds() {
        for (ServiceDefinition service : catalog.getServiceDefinitions()) {
            assertThat(service.isBindable()).isTrue();
            assertThat(service.isPlanUpdateable()).isFalse();
            List<Plan> plans = service.getPlans();
            assertThat(plans).extracting(Plan::getName).containsExactly("dev", "prod");
            for (Plan plan : plans) {
                assertThat(plan.isFree()).isTrue();
                assertThat(plan.isBindable()).isTrue();
                assertThat(plan.isPlanUpdateable()).isFalse();
            }
        }
    }

    @Test
    void planIdsMatchTheHardcodedConstants() {
        ServiceDefinition postgres = service("postgres");
        assertThat(postgres.getPlans()).extracting(Plan::getId)
                .containsExactly(CatalogIds.POSTGRES_DEV_PLAN_ID, CatalogIds.POSTGRES_PROD_PLAN_ID);

        ServiceDefinition mariadb = service("mariadb");
        assertThat(mariadb.getPlans()).extracting(Plan::getId)
                .containsExactly(CatalogIds.MARIADB_DEV_PLAN_ID, CatalogIds.MARIADB_PROD_PLAN_ID);
    }

    private ServiceDefinition service(String name) {
        return catalog.getServiceDefinitions().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
