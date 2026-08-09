package com.jaffan.broker.backend;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The physical backing database cluster (the BOSH postgres-ha deployment) that this broker carves
 * tenants out of.
 *
 * <p>Holds the admin connection pool pointed at the cluster's <em>maintenance</em> database
 * ({@code postgres}) — used for almost every DDL statement, since databases and roles are created
 * from there. The only exception is operations that must run <em>inside</em> a tenant database
 * (e.g. {@code CREATE EXTENSION}); {@link #openConnectionTo} hands out a short-lived admin
 * connection to a named database for exactly those cases.
 *
 * <p>{@code hosts} is the ordered list of cluster nodes; every URL the broker builds is a
 * multi-host URL with {@code targetServerType=primary}, so connections follow a postgres-ha
 * failover without any load balancer in between. {@code externalHosts} is the endpoint list
 * embedded in binding credentials when apps must reach the cluster on different addresses than the
 * broker uses; it defaults to {@code hosts}.
 */
public final class Backend {

    private final String key;
    private final DatabaseEngine engine;
    private final List<HostPort> hosts;
    private final List<HostPort> externalHosts;
    private final String adminUser;
    private final String adminPassword;
    private final DataSource maintenanceDataSource;
    private final JdbcTemplate jdbc;

    public Backend(String key, DatabaseEngine engine, List<HostPort> hosts,
            List<HostPort> externalHosts, String adminUser, String adminPassword,
            DataSource maintenanceDataSource) {
        this.key = Objects.requireNonNull(key);
        this.engine = Objects.requireNonNull(engine);
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalArgumentException("backend needs at least one host");
        }
        this.hosts = List.copyOf(hosts);
        this.externalHosts =
                (externalHosts == null || externalHosts.isEmpty()) ? this.hosts : List.copyOf(externalHosts);
        this.adminUser = Objects.requireNonNull(adminUser);
        this.adminPassword = Objects.requireNonNull(adminPassword);
        this.maintenanceDataSource = maintenanceDataSource;
        this.jdbc = maintenanceDataSource == null ? null : new JdbcTemplate(maintenanceDataSource);
    }

    /** Short stable label used in logs and the startup banner, e.g. {@code postgres-ha}. */
    public String key() {
        return key;
    }

    public DatabaseEngine engine() {
        return engine;
    }

    /** Cluster nodes the broker itself connects to. */
    public List<HostPort> hosts() {
        return hosts;
    }

    /** Endpoints embedded in app-facing binding credentials (may differ from {@link #hosts()}). */
    public List<HostPort> externalHosts() {
        return externalHosts;
    }

    /** Comma-joined {@code host:port} list for the broker's own connection URLs. */
    public String hostSpec() {
        return HostPort.join(hosts);
    }

    /** JdbcTemplate over the tiny admin pool bound to the maintenance database. */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /**
     * Open a fresh admin JDBC connection to a specific database on this cluster (routed to the
     * primary). Caller must close it (use try-with-resources). Used only for statements that must
     * execute inside a tenant DB.
     */
    public Connection openConnectionTo(String database) throws SQLException {
        String url = engine.jdbcScheme() + "://" + hostSpec() + "/" + database
                + "?targetServerType=primary";
        return java.sql.DriverManager.getConnection(url, adminUser, adminPassword);
    }

    /** Masked one-line description for the startup banner — never includes the password. */
    public String maskedDescription() {
        return "backend=" + key + " engine=" + engine + " hosts=" + hostSpec()
                + " externalHosts=" + HostPort.join(externalHosts) + " adminUser=" + adminUser
                + " maintenanceDb=" + engine.maintenanceDatabase();
    }
}
