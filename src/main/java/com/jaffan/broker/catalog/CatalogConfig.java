package com.jaffan.broker.catalog;

import org.springframework.cloud.servicebroker.model.catalog.Catalog;
import org.springframework.cloud.servicebroker.model.catalog.Plan;
import org.springframework.cloud.servicebroker.model.catalog.ServiceDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the OSB catalog: two services (postgres, mariadb), each with dev and prod plans. Publishing
 * a {@link Catalog} bean is all Spring Cloud Open Service Broker needs to serve {@code GET /v2/catalog}.
 *
 * <p>All plans are {@code bindable}, {@code free} and NOT {@code plan_updateable} — a plan change would
 * mean moving a tenant between separate dev/prod servers, which the broker refuses (HTTP 422).
 */
@Configuration
public class CatalogConfig {

    @Bean
    public Catalog catalog() {
        return Catalog.builder()
                .serviceDefinitions(postgresService(), mariadbService())
                .build();
    }

    private ServiceDefinition postgresService() {
        return ServiceDefinition.builder()
                .id(CatalogIds.POSTGRES_SERVICE_ID)
                .name("postgres")
                .description("Shared-instance PostgreSQL: a dedicated database and roles carved out of a "
                        + "shared PostgreSQL server.")
                .bindable(true)
                .planUpdateable(false)
                .tags("postgres", "postgresql", "relational", "sql")
                .plans(
                        plan(CatalogIds.POSTGRES_DEV_PLAN_ID, "dev",
                                "PostgreSQL tenant on the shared development server."),
                        plan(CatalogIds.POSTGRES_PROD_PLAN_ID, "prod",
                                "PostgreSQL tenant on the shared production server."))
                .build();
    }

    private ServiceDefinition mariadbService() {
        return ServiceDefinition.builder()
                .id(CatalogIds.MARIADB_SERVICE_ID)
                .name("mariadb")
                .description("Shared-instance MariaDB: a dedicated schema and users carved out of a "
                        + "shared MariaDB server.")
                .bindable(true)
                .planUpdateable(false)
                .tags("mariadb", "mysql", "relational", "sql")
                .plans(
                        plan(CatalogIds.MARIADB_DEV_PLAN_ID, "dev",
                                "MariaDB tenant on the shared development server."),
                        plan(CatalogIds.MARIADB_PROD_PLAN_ID, "prod",
                                "MariaDB tenant on the shared production server."))
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
