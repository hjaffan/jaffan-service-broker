package com.jaffan.broker.config;

import com.jaffan.broker.backend.Backend;
import com.jaffan.broker.backend.BackendRegistry;
import com.jaffan.broker.backend.DatabaseEngine;
import com.jaffan.broker.backend.HostPort;
import com.jaffan.broker.catalog.CatalogIds;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the postgres-ha cluster from environment variables into a {@link Backend} and a
 * {@link BackendRegistry}, and enforces fail-fast startup: the backend is probed with
 * {@code SELECT 1}, one masked line is logged (hosts and admin user, never the password), and the
 * context refuses to start if the cluster is unreachable.
 *
 * <p>{@code PG_HOST} may list several cluster nodes ({@code host[:port],...}); the admin pool uses a
 * multi-host JDBC URL with {@code targetServerType=primary}, so the broker follows a postgres-ha
 * failover automatically. The pool is tiny (max 2 connections) and points at the {@code postgres}
 * maintenance database.
 */
@Configuration
public class BackendConfig {

    private static final Logger log = LoggerFactory.getLogger(BackendConfig.class);

    @Bean(destroyMethod = "close")
    public HikariDataSource postgresDataSource(Environment env) {
        List<HostPort> hosts = hosts(env);
        String url = DatabaseEngine.POSTGRES.jdbcScheme() + "://" + HostPort.join(hosts) + "/"
                + DatabaseEngine.POSTGRES.maintenanceDatabase() + "?targetServerType=primary";

        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("postgres-ha-admin");
        ds.setJdbcUrl(url);
        ds.setUsername(required(env, "PG_ADMIN_USER"));
        ds.setPassword(required(env, "PG_ADMIN_PASSWORD"));
        ds.setMaximumPoolSize(2);
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(10_000);
        ds.setAutoCommit(true); // required so CREATE/ALTER DATABASE run outside a transaction
        // Defer the actual connection to our own SELECT 1 probe, so failures surface with a clear message.
        ds.setInitializationFailTimeout(-1);
        return ds;
    }

    @Bean
    public BackendRegistry backendRegistry(Environment env, HikariDataSource postgresDataSource) {
        List<HostPort> externalHosts = null;
        String externalRaw = env.getProperty("PG_EXTERNAL_HOST");
        if (externalRaw != null && !externalRaw.isBlank()) {
            externalHosts = HostPort.parseList(externalRaw, defaultPort(env));
        }

        Backend postgres = new Backend("postgres-ha", DatabaseEngine.POSTGRES, hosts(env),
                externalHosts, required(env, "PG_ADMIN_USER"), required(env, "PG_ADMIN_PASSWORD"),
                postgresDataSource);
        validate(postgres);

        return new BackendRegistry(Map.of(CatalogIds.POSTGRES_SHARED_PLAN_ID, postgres));
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

    private List<HostPort> hosts(Environment env) {
        return HostPort.parseList(required(env, "PG_HOST"), defaultPort(env));
    }

    /** Port for host entries without an explicit one: {@code PG_PORT}, defaulting to 5432. */
    private int defaultPort(Environment env) {
        String raw = env.getProperty("PG_PORT");
        if (raw == null || raw.isBlank()) {
            return DatabaseEngine.POSTGRES.defaultPort();
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("PG_PORT is not a valid integer: " + raw);
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
