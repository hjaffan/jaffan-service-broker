package com.jaffan.broker.provision;

import com.jaffan.broker.backend.Backend;
import com.jaffan.broker.backend.DatabaseEngine;
import com.jaffan.broker.naming.Identifiers;
import com.jaffan.broker.naming.PasswordGenerator;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL implementation of the tenant lifecycle.
 *
 * <p>The design goal is that unbind can never fail because an app created objects. We achieve that by
 * giving every instance a NOLOGIN <em>owner</em> role ({@code o_x}) that owns the database, and making
 * each binding login role ({@code b_y}) a member of it with {@code SET ROLE o_x} as its login default —
 * so every table/index/sequence the app creates is owned by {@code o_x}, not the ephemeral binding
 * role. Dropping {@code b_y} on unbind then only has to shed the CONNECT grant we handed it.
 *
 * <p>DDL that Postgres forbids inside a transaction ({@code CREATE/ALTER DATABASE}) is issued
 * through {@link JdbcTemplate#execute}, which runs on an autocommit connection — we never wrap these in
 * a transaction.
 *
 * <p>Deprovision never drops anything: it renames the tenant database to
 * {@code retired_<original>_<epochMillis>} and blocks connections to it (see {@link #retire}).
 */
public class PostgresProvisioner implements Provisioner {

    /** Only these extensions may be requested via the optional provision parameter. */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pgcrypto", "uuid-ossp", "pg_trgm");

    private final PasswordGenerator passwords;

    public PostgresProvisioner(PasswordGenerator passwords) {
        this.passwords = passwords;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.POSTGRES;
    }

    @Override
    public void provision(Backend backend, String instanceGuid, List<String> extensions) {
        JdbcTemplate jdbc = backend.jdbc();
        String db = Identifiers.instanceDatabase(instanceGuid, engine());
        String owner = Identifiers.ownerRole(instanceGuid, engine());
        String dbQ = Identifiers.quote(db, engine());
        String ownerQ = Identifiers.quote(owner, engine());

        // Owner role first (NOLOGIN group role), then the database it owns.
        if (!roleExists(jdbc, owner)) {
            jdbc.execute("CREATE ROLE " + ownerQ + " NOLOGIN");
        }
        // A non-superuser admin must be a member of o_x to create a database owned by it (and later
        // to rename it at retirement). CREATEROLE lets the admin self-grant on every PG version;
        // a plain no-op NOTICE if already a member.
        jdbc.execute("GRANT " + ownerQ + " TO CURRENT_USER");
        jdbc.execute("CREATE DATABASE " + dbQ + " OWNER " + ownerQ);
        jdbc.execute("REVOKE ALL ON DATABASE " + dbQ + " FROM PUBLIC");

        applyExtensions(backend, db, extensions);
    }

    private void applyExtensions(Backend backend, String db, List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return;
        }
        // CREATE EXTENSION must run inside the tenant database, so use a short-lived admin connection.
        try (Connection conn = backend.openConnectionTo(db); Statement st = conn.createStatement()) {
            for (String requested : extensions) {
                String ext = requested == null ? "" : requested.trim().toLowerCase();
                if (!ALLOWED_EXTENSIONS.contains(ext)) {
                    throw new IllegalArgumentException("extension not permitted: " + requested);
                }
                // ext is a member of a fixed whitelist of constants, safe to quote and concatenate.
                st.execute("CREATE EXTENSION IF NOT EXISTS \"" + ext + "\"");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed applying extensions to " + db, e);
        }
    }

    @Override
    public boolean databaseExists(Backend backend, String instanceGuid) {
        String db = Identifiers.instanceDatabase(instanceGuid, engine());
        return !backend.jdbc()
                .queryForList("SELECT 1 FROM pg_database WHERE datname = ?", Integer.class, db)
                .isEmpty();
    }

    @Override
    public Map<String, Object> bind(Backend backend, String instanceGuid, String bindingGuid) {
        JdbcTemplate jdbc = backend.jdbc();
        String db = Identifiers.instanceDatabase(instanceGuid, engine());
        String owner = Identifiers.ownerRole(instanceGuid, engine());
        String role = Identifiers.bindingRole(bindingGuid, engine());
        String dbQ = Identifiers.quote(db, engine());
        String ownerQ = Identifiers.quote(owner, engine());
        String roleQ = Identifiers.quote(role, engine());
        String password = passwords.generate();

        // password is 32 alphanumerics (see PasswordGenerator) — no quote/backslash can appear.
        jdbc.execute("CREATE ROLE " + roleQ + " LOGIN PASSWORD '" + password
                + "' CONNECTION LIMIT 20 IN ROLE " + ownerQ);
        // Make the app act as the instance owner, so everything it creates is owned by o_x.
        jdbc.execute("ALTER ROLE " + roleQ + " SET ROLE " + ownerQ);
        jdbc.execute("ALTER ROLE " + roleQ + " SET statement_timeout = '120s'");
        jdbc.execute("GRANT CONNECT ON DATABASE " + dbQ + " TO " + roleQ);

        return BindingCredentials.build(backend, db, role, password);
    }

    @Override
    public boolean bindingExists(Backend backend, String bindingGuid) {
        return roleExists(backend.jdbc(), Identifiers.bindingRole(bindingGuid, engine()));
    }

    @Override
    public void unbind(Backend backend, String instanceGuid, String bindingGuid) {
        JdbcTemplate jdbc = backend.jdbc();
        String db = Identifiers.instanceDatabase(instanceGuid, engine());
        String role = Identifiers.bindingRole(bindingGuid, engine());
        String dbQ = Identifiers.quote(db, engine());
        String roleQ = Identifiers.quote(role, engine());

        if (!roleExists(jdbc, role)) {
            return; // already gone — idempotent
        }
        // Kill the binding's live sessions so the DROP is not blocked by an in-use role.
        jdbc.queryForList("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = ?",
                Object.class, role);
        // The only dependency b_y holds is the CONNECT grant (objects it "created" are owned by o_x).
        jdbc.execute("REVOKE ALL ON DATABASE " + dbQ + " FROM " + roleQ);
        jdbc.execute("DROP ROLE IF EXISTS " + roleQ);
    }

    @Override
    public void retire(Backend backend, String instanceGuid) {
        JdbcTemplate jdbc = backend.jdbc();
        String db = Identifiers.instanceDatabase(instanceGuid, engine());
        String dbQ = Identifiers.quote(db, engine());

        // Terminate every connection to the tenant DB — required before the rename.
        jdbc.queryForList("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = ?",
                Object.class, db);

        long epoch = System.currentTimeMillis();
        String retired = Identifiers.retiredName(db, epoch, engine());
        String retiredQ = Identifiers.quote(retired, engine());
        // Rename out of the way; keep o_x since it still owns the retired database.
        jdbc.execute("ALTER DATABASE " + dbQ + " RENAME TO " + retiredQ);
        // Freeze the retired copy: nobody (not even admin) connects until an operator re-enables it
        // with ALTER DATABASE ... WITH ALLOW_CONNECTIONS true.
        jdbc.execute("ALTER DATABASE " + retiredQ + " WITH ALLOW_CONNECTIONS false");
    }

    private boolean roleExists(JdbcTemplate jdbc, String role) {
        return !jdbc.queryForList("SELECT 1 FROM pg_roles WHERE rolname = ?", Integer.class, role)
                .isEmpty();
    }
}
