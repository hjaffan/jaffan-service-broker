package com.jaffan.broker.provision;

/**
 * How deprovision disposes of a tenant database, chosen by the {@code DEPROVISION_MODE} env var.
 *
 * <ul>
 *   <li>{@link #SOFT} (default) — rename/park the data out of the way but keep it recoverable, so an
 *       accidental {@code cf delete-service} is survivable. Purge is a documented manual step.</li>
 *   <li>{@link #DROP} — hard {@code DROP DATABASE} (and the owner role, for Postgres). Irreversible.</li>
 * </ul>
 */
public enum DeprovisionMode {

    SOFT,
    DROP;

    public static DeprovisionMode fromEnv(String raw) {
        if (raw == null || raw.isBlank()) {
            return SOFT;
        }
        return switch (raw.trim().toLowerCase()) {
            case "soft" -> SOFT;
            case "drop" -> DROP;
            default -> throw new IllegalArgumentException(
                    "DEPROVISION_MODE must be 'soft' or 'drop', got: " + raw);
        };
    }
}
