package com.jaffan.broker.provision;

import com.jaffan.broker.backend.Backend;
import com.jaffan.broker.backend.DatabaseEngine;
import com.jaffan.broker.naming.Identifiers;
import com.jaffan.broker.naming.PasswordGenerator;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MariaDB implementation of the tenant lifecycle.
 *
 * <p>MariaDB has no owner-role concept, so a tenant is just a schema plus per-binding users granted on
 * it. All privileges granted to a binding user are scoped to {@code `si_x`.*}, so dropping the user on
 * unbind never touches the data.
 *
 * <p>Binding users are created as {@code 'b_y'@'%'}. Both the schema name and the user name pass
 * through {@link Identifiers} (so they are {@code [a-z0-9_]} only), and passwords are 32 alphanumerics,
 * which is why interpolating them into {@code CREATE USER ... IDENTIFIED BY '...'} is safe.
 */
public class MariaDbProvisioner implements Provisioner {

    private final PasswordGenerator passwords;

    public MariaDbProvisioner(PasswordGenerator passwords) {
        this.passwords = passwords;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.MARIADB;
    }

    @Override
    public void provision(Backend backend, String instanceGuid, List<String> extensions) {
        String db = Identifiers.instanceDatabase(instanceGuid, engine());
        String dbQ = Identifiers.quote(db, engine());
        backend.jdbc().execute("CREATE DATABASE " + dbQ
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        // MariaDB has no CREATE EXTENSION equivalent; the optional extensions parameter is ignored.
    }

    @Override
    public boolean databaseExists(Backend backend, String instanceGuid) {
        String db = Identifiers.instanceDatabase(instanceGuid, engine());
        return !backend.jdbc()
                .queryForList("SELECT 1 FROM information_schema.SCHEMATA WHERE schema_name = ?",
                        Integer.class, db)
                .isEmpty();
    }

    @Override
    public Map<String, Object> bind(Backend backend, String instanceGuid, String bindingGuid) {
        JdbcTemplate jdbc = backend.jdbc();
        String db = Identifiers.instanceDatabase(instanceGuid, engine());
        String user = Identifiers.bindingRole(bindingGuid, engine());
        String dbQ = Identifiers.quote(db, engine());
        String userSpec = userSpec(user);
        String password = passwords.generate();

        jdbc.execute("CREATE USER " + userSpec + " IDENTIFIED BY '" + password
                + "' WITH MAX_USER_CONNECTIONS 20");
        jdbc.execute("GRANT ALL PRIVILEGES ON " + dbQ + ".* TO " + userSpec);

        return BindingCredentials.build(backend, db, user, password);
    }

    @Override
    public boolean bindingExists(Backend backend, String bindingGuid) {
        String user = Identifiers.bindingRole(bindingGuid, engine());
        return !backend.jdbc()
                .queryForList("SELECT 1 FROM mysql.user WHERE User = ? AND Host = '%'",
                        Integer.class, user)
                .isEmpty();
    }

    @Override
    public void unbind(Backend backend, String instanceGuid, String bindingGuid) {
        JdbcTemplate jdbc = backend.jdbc();
        String user = Identifiers.bindingRole(bindingGuid, engine());

        // Kill the user's live sessions (KILL takes a numeric id from the server, not user input).
        for (Long id : jdbc.queryForList(
                "SELECT id FROM information_schema.PROCESSLIST WHERE user = ?", Long.class, user)) {
            try {
                jdbc.execute("KILL " + id);
            } catch (RuntimeException ignored) {
                // The session may have ended between the SELECT and the KILL; that is fine.
            }
        }
        jdbc.execute("DROP USER IF EXISTS " + userSpec(user));
    }

    @Override
    public void deprovision(Backend backend, String instanceGuid, DeprovisionMode mode) {
        JdbcTemplate jdbc = backend.jdbc();
        String db = Identifiers.instanceDatabase(instanceGuid, engine());
        String dbQ = Identifiers.quote(db, engine());

        killDatabaseSessions(jdbc, db);

        if (mode == DeprovisionMode.SOFT) {
            long epoch = System.currentTimeMillis();
            String parked = Identifiers.deletedName(db, epoch, engine());
            String parkedQ = Identifiers.quote(parked, engine());
            jdbc.execute("CREATE DATABASE " + parkedQ
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            // Move every base table across; views/routines are NOT moved (documented in the README).
            List<String> tables = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.TABLES "
                            + "WHERE table_schema = ? AND table_type = 'BASE TABLE'",
                    String.class, db);
            for (String table : tables) {
                // App-created table names are arbitrary (mixed case etc.), so they are NOT broker
                // identifiers — quote them by backtick-escaping rather than validating against [a-z0-9_].
                String tableQ = backtickQuote(table);
                jdbc.execute("RENAME TABLE " + dbQ + "." + tableQ + " TO " + parkedQ + "." + tableQ);
            }
            jdbc.execute("DROP DATABASE " + dbQ);
        } else {
            jdbc.execute("DROP DATABASE IF EXISTS " + dbQ);
        }
    }

    private void killDatabaseSessions(JdbcTemplate jdbc, String db) {
        for (Long id : jdbc.queryForList(
                "SELECT id FROM information_schema.PROCESSLIST WHERE db = ?", Long.class, db)) {
            try {
                jdbc.execute("KILL " + id);
            } catch (RuntimeException ignored) {
                // session already gone
            }
        }
    }

    /** Build the {@code 'name'@'%'} account spec for a validated identifier. */
    private String userSpec(String user) {
        Identifiers.validate(user, engine());
        return "'" + user + "'@'%'";
    }

    /**
     * Backtick-quote an <em>arbitrary</em> MariaDB identifier (e.g. an app-created table name) by
     * doubling any embedded backticks. Unlike {@link Identifiers#quote}, this does not require the
     * identifier to be a broker-generated {@code [a-z0-9_]} name.
     */
    private String backtickQuote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
