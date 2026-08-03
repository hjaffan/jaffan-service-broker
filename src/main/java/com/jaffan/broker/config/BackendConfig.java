package com.jaffan.broker.config;

import com.jaffan.broker.backend.Backend;
import com.jaffan.broker.backend.BackendRegistry;
import com.jaffan.broker.backend.DatabaseEngine;
import com.jaffan.broker.catalog.CatalogIds;
import com.jaffan.broker.provision.DeprovisionMode;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the four backing database servers from environment variables into {@link Backend}s and a
 * {@link BackendRegistry}, and enforces fail-fast startup: every configured backend is probed with
 * {@code SELECT 1}, one masked line is logged per backend (host and admin user, never the password),
 * and the context refuses to start if any backend is unreachable.
 *
 * <p>Each backend gets its own tiny HikariCP pool (max 2 connections) pointed at that engine's
 * maintenance database ({@code postgres} / {@code mysql}).
 */
@Configuration
public class BackendConfig {

    private static final Logger log = LoggerFactory.getLogger(BackendConfig.class);

    @Bean(destroyMethod = "close")
    public HikariDataSource pgDevDataSource(Environment env) {
        return pool(env, "PG_DEV", DatabaseEngine.POSTGRES);
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource pgProdDataSource(Environment env) {
        return pool(env, "PG_PROD", DatabaseEngine.POSTGRES);
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource mariaDevDataSource(Environment env) {
        return pool(env, "MARIA_DEV", DatabaseEngine.MARIADB);
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource mariaProdDataSource(Environment env) {
        return pool(env, "MARIA_PROD", DatabaseEngine.MARIADB);
    }

    @Bean
    public DeprovisionMode deprovisionMode(Environment env) {
        DeprovisionMode mode = DeprovisionMode.fromEnv(env.getProperty("DEPROVISION_MODE"));
        log.info("startup deprovision_mode={}", mode.name().toLowerCase());
        return mode;
    }

    @Bean
    public BackendRegistry backendRegistry(Environment env,
            HikariDataSource pgDevDataSource, HikariDataSource pgProdDataSource,
            HikariDataSource mariaDevDataSource, HikariDataSource mariaProdDataSource) {

        Backend pgDev = backend(env, "pg-dev", "PG_DEV", DatabaseEngine.POSTGRES, pgDevDataSource);
        Backend pgProd = backend(env, "pg-prod", "PG_PROD", DatabaseEngine.POSTGRES, pgProdDataSource);
        Backend mariaDev = backend(env, "maria-dev", "MARIA_DEV", DatabaseEngine.MARIADB, mariaDevDataSource);
        Backend mariaProd = backend(env, "maria-prod", "MARIA_PROD", DatabaseEngine.MARIADB, mariaProdDataSource);

        for (Backend backend : List.of(pgDev, pgProd, mariaDev, mariaProd)) {
            validate(backend);
        }

        Map<String, Backend> byPlan = Map.of(
                CatalogIds.POSTGRES_DEV_PLAN_ID, pgDev,
                CatalogIds.POSTGRES_PROD_PLAN_ID, pgProd,
                CatalogIds.MARIADB_DEV_PLAN_ID, mariaDev,
                CatalogIds.MARIADB_PROD_PLAN_ID, mariaProd);

        Map<String, List<String>> siblings = Map.of(
                CatalogIds.POSTGRES_DEV_PLAN_ID, List.of(CatalogIds.POSTGRES_PROD_PLAN_ID),
                CatalogIds.POSTGRES_PROD_PLAN_ID, List.of(CatalogIds.POSTGRES_DEV_PLAN_ID),
                CatalogIds.MARIADB_DEV_PLAN_ID, List.of(CatalogIds.MARIADB_PROD_PLAN_ID),
                CatalogIds.MARIADB_PROD_PLAN_ID, List.of(CatalogIds.MARIADB_DEV_PLAN_ID));

        return new BackendRegistry(byPlan, siblings);
    }

    /** Build the maintenance-DB connection pool for one backend from its {@code <PREFIX>_*} env vars. */
    private HikariDataSource pool(Environment env, String prefix, DatabaseEngine engine) {
        String host = required(env, prefix + "_HOST");
        int port = port(env, prefix, engine);
        String user = required(env, prefix + "_ADMIN_USER");
        String password = required(env, prefix + "_ADMIN_PASSWORD");

        String url = engine.jdbcScheme() + "://" + host + ":" + port + "/" + engine.maintenanceDatabase();

        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName(prefix.toLowerCase().replace('_', '-') + "-admin");
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setMaximumPoolSize(2);
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(10_000);
        ds.setAutoCommit(true); // required so CREATE/DROP DATABASE run outside a transaction
        // Defer the actual connection to our own SELECT 1 probe, so failures surface with a clear message.
        ds.setInitializationFailTimeout(-1);
        return ds;
    }

    private Backend backend(Environment env, String key, String prefix, DatabaseEngine engine,
            HikariDataSource dataSource) {
        String host = required(env, prefix + "_HOST");
        int port = port(env, prefix, engine);
        String externalHost = env.getProperty(prefix + "_EXTERNAL_HOST");
        String user = required(env, prefix + "_ADMIN_USER");
        String password = required(env, prefix + "_ADMIN_PASSWORD");
        return new Backend(key, engine, host, port, externalHost, user, password, dataSource);
    }

    /** Probe the backend with {@code SELECT 1}; log a masked line on success, fail fast on error. */
    private void validate(Backend backend) {
        try {
            Integer one = backend.jdbc().queryForObject("SELECT 1", Integer.class);
            if (one == null || one != 1) {
                throw new IllegalStateException("SELECT 1 returned " + one);
            }
            log.info("startup backend validated: {}", backend.maskedDescription());
        } catch (RuntimeException e) {
            // Fail fast: aborting bean creation prevents the app from starting with a broken backend.
            throw new IllegalStateException("startup validation failed for " + backend.maskedDescription()
                    + " (" + e.getMessage() + ")", e);
        }
    }

    private int port(Environment env, String prefix, DatabaseEngine engine) {
        String raw = env.getProperty(prefix + "_PORT");
        if (raw == null || raw.isBlank()) {
            return engine.defaultPort();
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(prefix + "_PORT is not a valid integer: " + raw);
        }
    }

    private String required(Environment env, String name) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required environment variable is not set: " + name);
        }
        return value;
    }
}
