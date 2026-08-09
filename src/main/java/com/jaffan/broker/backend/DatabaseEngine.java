package com.jaffan.broker.backend;

/**
 * The database engines this broker can carve tenants out of. Every backend, provisioner and
 * naming decision keys off this enum, so identifier length limits and URI schemes live here.
 * Currently only PostgreSQL (the BOSH postgres-ha cluster); kept as an enum so a second engine
 * can be added without reshaping the code.
 */
public enum DatabaseEngine {

    POSTGRES("postgresql", "jdbc:postgresql", 5432, "postgres", 63);

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

    /** Scheme for the app-facing {@code uri} credential, e.g. {@code postgresql://}. */
    public String uriScheme() {
        return uriScheme;
    }

    /** Scheme for the app-facing {@code jdbcUrl} credential, e.g. {@code jdbc:postgresql}. */
    public String jdbcScheme() {
        return jdbcScheme;
    }

    public int defaultPort() {
        return defaultPort;
    }

    /** The admin/maintenance database the broker's pool connects to ({@code postgres}). */
    public String maintenanceDatabase() {
        return maintenanceDatabase;
    }

    public int maxIdentifierLength() {
        return maxIdentifierLength;
    }
}
