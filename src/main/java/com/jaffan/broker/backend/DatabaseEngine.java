package com.jaffan.broker.backend;

/**
 * The two database engines this broker can carve tenants out of. Every backend, provisioner and
 * naming decision keys off this enum, so identifier length limits and URI schemes live here.
 */
public enum DatabaseEngine {

    POSTGRES("postgresql", "jdbc:postgresql", 5432, "postgres", 63),
    MARIADB("mysql", "jdbc:mariadb", 3306, "mysql", 64);

    private final String uriScheme;
    private final String jdbcScheme;
    private final int defaultPort;
    private final String maintenanceDatabase;
    private final int maxIdentifierLength;

    DatabaseEngine(String uriScheme, String jdbcScheme, int defaultPort, String maintenanceDatabase,
            int maxIdentifierLength) {
        this.uriScheme = uriScheme;
        this.jdbcScheme = jdbcScheme;
        this.defaultPort = defaultPort;
        this.maintenanceDatabase = maintenanceDatabase;
        this.maxIdentifierLength = maxIdentifierLength;
    }

    /** Scheme for the app-facing {@code uri} credential, e.g. {@code postgresql://} / {@code mysql://}. */
    public String uriScheme() {
        return uriScheme;
    }

    /** Scheme for the app-facing {@code jdbcUrl} credential, e.g. {@code jdbc:postgresql} / {@code jdbc:mariadb}. */
    public String jdbcScheme() {
        return jdbcScheme;
    }

    public int defaultPort() {
        return defaultPort;
    }

    /** The admin/maintenance database the broker's pool connects to ({@code postgres} / {@code mysql}). */
    public String maintenanceDatabase() {
        return maintenanceDatabase;
    }

    public int maxIdentifierLength() {
        return maxIdentifierLength;
    }
}
