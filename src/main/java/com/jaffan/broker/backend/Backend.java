package com.jaffan.broker.backend;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One physical backing database server (e.g. "Postgres prod") that this broker carves tenants out of.
 *
 * <p>Holds the admin connection pool pointed at the server's <em>maintenance</em> database
 * ({@code postgres} / {@code mysql}) — used for almost every DDL statement, since both engines let you
 * create/drop databases, roles and users from there. The only exception is operations that must run
 * <em>inside</em> a tenant database (e.g. Postgres {@code CREATE EXTENSION}); {@link #openConnectionTo}
 * hands out a short-lived admin connection to a named database for exactly those cases.
 *
 * <p>{@code externalHost} is the address embedded in binding credentials when apps must reach the
 * server on a different name than the broker uses; it defaults to {@code host}.
 */
public final class Backend {

    private final String key;
    private final DatabaseEngine engine;
    private final String host;
    private final int port;
    private final String externalHost;
    private final String adminUser;
    private final String adminPassword;
    private final DataSource maintenanceDataSource;
    private final JdbcTemplate jdbc;

    public Backend(String key, DatabaseEngine engine, String host, int port, String externalHost,
            String adminUser, String adminPassword, DataSource maintenanceDataSource) {
        this.key = Objects.requireNonNull(key);
        this.engine = Objects.requireNonNull(engine);
        this.host = Objects.requireNonNull(host);
        this.port = port;
        this.externalHost = (externalHost == null || externalHost.isBlank()) ? host : externalHost;
        this.adminUser = Objects.requireNonNull(adminUser);
        this.adminPassword = Objects.requireNonNull(adminPassword);
        this.maintenanceDataSource = maintenanceDataSource;
        this.jdbc = maintenanceDataSource == null ? null : new JdbcTemplate(maintenanceDataSource);
    }

    /** Short stable label used in logs and the startup banner, e.g. {@code pg-prod}. */
    public String key() {
        return key;
    }

    public DatabaseEngine engine() {
        return engine;
    }

    /** Address the broker itself connects to. */
    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /** Address embedded in app-facing binding credentials (may differ from {@link #host()}). */
    public String externalHost() {
        return externalHost;
    }

    /** JdbcTemplate over the tiny admin pool bound to the maintenance database. */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /**
     * Open a fresh admin JDBC connection to a specific database on this server. Caller must close it
     * (use try-with-resources). Used only for statements that must execute inside a tenant DB.
     */
    public Connection openConnectionTo(String database) throws SQLException {
        String url = engine.jdbcScheme() + "://" + host + ":" + port + "/" + database;
        return java.sql.DriverManager.getConnection(url, adminUser, adminPassword);
    }

    /** Masked one-line description for the startup banner — never includes the password. */
    public String maskedDescription() {
        return "backend=" + key + " engine=" + engine + " host=" + host + " port=" + port
                + " externalHost=" + externalHost + " adminUser=" + adminUser
                + " maintenanceDb=" + engine.maintenanceDatabase();
    }
}
