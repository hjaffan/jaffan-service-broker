package com.jaffan.broker.catalog;

import org.springframework.cloud.servicebroker.model.catalog.Catalog;
import org.springframework.cloud.servicebroker.model.catalog.Plan;
import org.springframework.cloud.servicebroker.model.catalog.ServiceDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the OSB catalog: one service (postgres) with one plan (shared), backed by the BOSH
 * postgres-ha cluster. Publishing a {@link Catalog} bean is all Spring Cloud Open Service Broker
 * needs to serve {@code GET /v2/catalog}.
 *
 * <p>The plan is {@code bindable}, {@code free} and NOT {@code plan_updateable} — there is exactly
 * one plan, so a plan change is meaningless and refused (HTTP 422).
 */
@Configuration
public class CatalogConfig {

    @Bean
    public Catalog catalog() {
        return Catalog.builder()
                .serviceDefinitions(postgresService())
                .build();
    }

    private ServiceDefinition postgresService() {
        return ServiceDefinition.builder()
                .id(CatalogIds.POSTGRES_SERVICE_ID)
                .name("postgres")
                .description("Shared-instance PostgreSQL: a dedicated database and roles carved out "
                        + "of the shared postgres-ha cluster. Deleting an instance retires its "
                        + "database (parks it under a retired_ name) — data is never dropped.")
                .bindable(true)
                .planUpdateable(false)
                .tags("postgres", "postgresql", "relational", "sql")
                .plans(
                        plan(CatalogIds.POSTGRES_SHARED_PLAN_ID, "shared",
                                "A dedicated database on the shared postgres-ha PostgreSQL cluster."))
                .build();
    }

    private Plan plan(String id, String name, String description) {
        return Plan.builder()
                .id(id)
                .name(name)
                .description(description)
                .free(true)
                .bindable(true)
                .planUpdateable(false)
                .build();
    }
}
