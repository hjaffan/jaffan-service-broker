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
    void exposesThePostgresServiceWithTheFixedId() {
        assertThat(catalog.getServiceDefinitions())
                .extracting(ServiceDefinition::getId)
                .containsExactly(CatalogIds.POSTGRES_SERVICE_ID);
        assertThat(catalog.getServiceDefinitions())
                .extracting(ServiceDefinition::getName)
                .containsExactly("postgres");
    }

    @Test
    void theSharedPlanIsBindableFreeAndNotUpdateableWithTheFixedId() {
        ServiceDefinition postgres = catalog.getServiceDefinitions().get(0);
        assertThat(postgres.isBindable()).isTrue();
        assertThat(postgres.isPlanUpdateable()).isFalse();

        List<Plan> plans = postgres.getPlans();
        assertThat(plans).extracting(Plan::getName).containsExactly("shared");
        assertThat(plans).extracting(Plan::getId).containsExactly(CatalogIds.POSTGRES_SHARED_PLAN_ID);
        for (Plan plan : plans) {
            assertThat(plan.isFree()).isTrue();
            assertThat(plan.isBindable()).isTrue();
            assertThat(plan.isPlanUpdateable()).isFalse();
        }
    }
}
