package com.jaffan.broker.naming;

import com.jaffan.broker.backend.DatabaseEngine;

/**
 * The single choke-point for turning OSB GUIDs into SQL object names and for quoting them safely.
 *
 * <p>Statelessness of the whole broker rests on this class: every database, role and user name is a
 * pure function of the instance/binding GUID, so nothing has to be remembered between requests. The
 * flip side is that these names are interpolated into DDL that cannot be parameterised
 * ({@code CREATE DATABASE}, {@code CREATE ROLE} ...), so <b>every</b> identifier must pass through
 * {@link #validate} — which both sanitises and hard-rejects anything outside {@code [a-z0-9_]} — before
 * it is quoted and concatenated. No caller should build an identifier by hand.
 */
public final class Identifiers {

    /** After sanitisation an identifier must match exactly this, or we refuse to touch the database. */
    private static final String ALLOWED = "[a-z0-9_]+";

    private Identifiers() {
    }

    /** Lower-case and swap hyphens for underscores. CF GUIDs are already lower-case hex + hyphens. */
    public static String underscore(String guid) {
        if (guid == null) {
            throw new IllegalArgumentException("identifier source must not be null");
        }
        return guid.toLowerCase().replace('-', '_');
    }

    /** Instance database name: {@code si_<instance_guid>}. */
    public static String instanceDatabase(String instanceGuid, DatabaseEngine engine) {
        return validate("si_" + underscore(instanceGuid), engine);
    }

    /** Postgres per-instance owner role (NOLOGIN): {@code o_<instance_guid>}. */
    public static String ownerRole(String instanceGuid, DatabaseEngine engine) {
        return validate("o_" + underscore(instanceGuid), engine);
    }

    /** Binding login role / user: {@code b_<binding_guid>}. */
    public static String bindingRole(String bindingGuid, DatabaseEngine engine) {
        return validate("b_" + underscore(bindingGuid), engine);
    }

    /**
     * Name a soft-deleted database gets renamed/moved to: {@code deleted_<original>_<epochMillis>}.
     * Still length-validated so we never emit a name Postgres/MariaDB would silently truncate.
     */
    public static String deletedName(String originalDatabase, long epochMillis, DatabaseEngine engine) {
        return validate("deleted_" + originalDatabase + "_" + epochMillis, engine);
    }

    /**
     * Sanitise and validate an identifier for the given engine. Throws if, after lower-casing, the
     * identifier contains anything other than {@code [a-z0-9_]} or exceeds the engine's length limit.
     */
    public static String validate(String identifier, DatabaseEngine engine) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("identifier must not be empty");
        }
        String lower = identifier.toLowerCase();
        if (!lower.equals(identifier)) {
            throw new IllegalArgumentException("identifier must be lower-case: " + identifier);
        }
        if (!identifier.matches(ALLOWED)) {
            throw new IllegalArgumentException(
                    "identifier contains characters outside [a-z0-9_]: " + identifier);
        }
        int max = engine.maxIdentifierLength();
        if (identifier.length() > max) {
            throw new IllegalArgumentException(
                    "identifier '" + identifier + "' exceeds " + max + " chars for " + engine);
        }
        return identifier;
    }

    /**
     * Quote a <b>previously validated</b> identifier for the target engine. Because {@link #validate}
     * guarantees {@code [a-z0-9_]} only, the quote character can never appear inside the identifier, so
     * this cannot be used to break out of the quotes. Postgres uses double quotes, MariaDB backticks.
     */
    public static String quote(String identifier, DatabaseEngine engine) {
        validate(identifier, engine);
        return switch (engine) {
            case POSTGRES -> "\"" + identifier + "\"";
            case MARIADB -> "`" + identifier + "`";
        };
    }
}
