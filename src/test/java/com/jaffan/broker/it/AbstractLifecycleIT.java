package com.jaffan.broker.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaffan.broker.backend.Backend;
import com.jaffan.broker.backend.DatabaseEngine;
import com.jaffan.broker.naming.Identifiers;
import com.jaffan.broker.provision.DeprovisionMode;
import com.jaffan.broker.provision.Provisioner;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end lifecycle exercised against a real database container: provision -> bind -> use ->
 * unbind -> deprovision, for whichever engine the subclass wires up.
 *
 * <p>{@code disabledWithoutDocker = true} makes the whole class self-skip when no Docker daemon is
 * present, so {@code mvn package} (which does not run these {@code *IT} classes anyway) and
 * {@code mvn verify} on Docker-less machines both stay green.
 */
@Testcontainers(disabledWithoutDocker = true)
abstract class AbstractLifecycleIT {

    // Distinct GUIDs per scenario so the shared, class-scoped container never sees cross-test bleed.
    protected static final String INSTANCE_LIFECYCLE = "11111111-1111-1111-1111-111111111111";
    protected static final String INSTANCE_SHARED = "22222222-2222-2222-2222-222222222222";
    protected static final String INSTANCE_TABLE = "33333333-3333-3333-3333-333333333333";
    protected static final String INSTANCE_SOFT = "44444444-4444-4444-4444-444444444444";
    protected static final String INSTANCE_DROP = "55555555-5555-5555-5555-555555555555";

    protected static final String BINDING_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    protected static final String BINDING_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    /** A live backend pointed at the running container (built by the subclass after container start). */
    protected abstract Backend backend();

    protected abstract Provisioner provisioner();

    protected DatabaseEngine engine() {
        return provisioner().engine();
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void fullProvisionBindUseUnbindDeprovisionLifecycle() throws Exception {
        Backend backend = backend();
        Provisioner provisioner = provisioner();

        provisioner.provision(backend, INSTANCE_LIFECYCLE, List.of());
        assertThat(provisioner.databaseExists(backend, INSTANCE_LIFECYCLE)).isTrue();

        Map<String, Object> creds = provisioner.bind(backend, INSTANCE_LIFECYCLE, BINDING_A);
        assertThat(creds).containsKeys("uri", "jdbcUrl", "host", "port", "database", "username", "password");
        assertThat(creds.get("username")).isEqualTo(Identifiers.bindingRole(BINDING_A, engine()));
        assertThat(creds.get("database")).isEqualTo(Identifiers.instanceDatabase(INSTANCE_LIFECYCLE, engine()));

        // Use the credentials for real work.
        try (Connection conn = connectAs(creds); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE widgets (id INT PRIMARY KEY, name VARCHAR(50))");
            st.execute("INSERT INTO widgets (id, name) VALUES (1, 'gadget')");
            assertThat(countRows(conn, "widgets")).isEqualTo(1);
        }
        assertThat(provisioner.bindingExists(backend, BINDING_A)).isTrue();

        provisioner.unbind(backend, INSTANCE_LIFECYCLE, BINDING_A);
        assertThat(provisioner.bindingExists(backend, BINDING_A)).isFalse();

        provisioner.deprovision(backend, INSTANCE_LIFECYCLE, DeprovisionMode.DROP);
        assertThat(provisioner.databaseExists(backend, INSTANCE_LIFECYCLE)).isFalse();
    }

    @Test
    void twoBindingsYieldDistinctCredentialsAndAreIndependent() throws Exception {
        Backend backend = backend();
        Provisioner provisioner = provisioner();
        provisioner.provision(backend, INSTANCE_SHARED, List.of());

        Map<String, Object> credsA = provisioner.bind(backend, INSTANCE_SHARED, BINDING_A);
        Map<String, Object> credsB = provisioner.bind(backend, INSTANCE_SHARED, BINDING_B);

        // Distinct credentials.
        assertThat(credsA.get("username")).isNotEqualTo(credsB.get("username"));
        assertThat(credsA.get("password")).isNotEqualTo(credsB.get("password"));

        // Both connect; a row written via A is visible via B (shared instance).
        try (Connection connA = connectAs(credsA); Statement st = connA.createStatement()) {
            st.execute("CREATE TABLE shared_data (id INT PRIMARY KEY)");
            st.execute("INSERT INTO shared_data (id) VALUES (42)");
        }
        try (Connection connB = connectAs(credsB)) {
            assertThat(countRows(connB, "shared_data")).isEqualTo(1);
        }

        // Dropping binding A leaves binding B and all data intact.
        provisioner.unbind(backend, INSTANCE_SHARED, BINDING_A);
        assertThat(provisioner.bindingExists(backend, BINDING_A)).isFalse();
        assertThat(provisioner.bindingExists(backend, BINDING_B)).isTrue();

        try (Connection connB = connectAs(credsB)) {
            assertThat(countRows(connB, "shared_data")).isEqualTo(1);
        }

        provisioner.unbind(backend, INSTANCE_SHARED, BINDING_B);
        provisioner.deprovision(backend, INSTANCE_SHARED, DeprovisionMode.DROP);
    }

    @Test
    void appCreatesTableThenUnbindSucceedsAndSecondBindingSeesTable() throws Exception {
        Backend backend = backend();
        Provisioner provisioner = provisioner();
        provisioner.provision(backend, INSTANCE_TABLE, List.of());

        // First binding: the app creates a table (owned by the instance role, for Postgres).
        Map<String, Object> credsA = provisioner.bind(backend, INSTANCE_TABLE, BINDING_A);
        try (Connection connA = connectAs(credsA); Statement st = connA.createStatement()) {
            st.execute("CREATE TABLE app_owned (id INT PRIMARY KEY, note VARCHAR(50))");
            st.execute("INSERT INTO app_owned (id, note) VALUES (7, 'created-by-binding-A')");
        }

        // Unbind must NOT fail even though the app created objects.
        provisioner.unbind(backend, INSTANCE_TABLE, BINDING_A);
        assertThat(provisioner.bindingExists(backend, BINDING_A)).isFalse();

        // A second binding still sees the table and its data.
        Map<String, Object> credsB = provisioner.bind(backend, INSTANCE_TABLE, BINDING_B);
        try (Connection connB = connectAs(credsB); Statement st = connB.createStatement()) {
            assertThat(countRows(connB, "app_owned")).isEqualTo(1);
            // And can keep writing to it.
            st.execute("INSERT INTO app_owned (id, note) VALUES (8, 'created-by-binding-B')");
            assertThat(countRows(connB, "app_owned")).isEqualTo(2);
        }

        provisioner.unbind(backend, INSTANCE_TABLE, BINDING_B);
        provisioner.deprovision(backend, INSTANCE_TABLE, DeprovisionMode.DROP);
    }

    @Test
    void softDeprovisionParksTheDatabaseInsteadOfDroppingIt() throws Exception {
        Backend backend = backend();
        Provisioner provisioner = provisioner();
        provisioner.provision(backend, INSTANCE_SOFT, List.of());

        Map<String, Object> creds = provisioner.bind(backend, INSTANCE_SOFT, BINDING_A);
        try (Connection conn = connectAs(creds); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE keep_me (id INT PRIMARY KEY)");
            st.execute("INSERT INTO keep_me (id) VALUES (1)");
        }
        provisioner.unbind(backend, INSTANCE_SOFT, BINDING_A);

        String original = Identifiers.instanceDatabase(INSTANCE_SOFT, engine());
        provisioner.deprovision(backend, INSTANCE_SOFT, DeprovisionMode.SOFT);

        // Original name is gone, but a parked deleted_* database now exists.
        assertThat(provisioner.databaseExists(backend, INSTANCE_SOFT)).isFalse();
        List<String> parked = parkedDatabasesFor(backend, original);
        assertThat(parked).as("a deleted_* database should exist after soft deprovision").isNotEmpty();

        // Clean up the parked database(s).
        for (String db : parked) {
            dropDatabase(backend, db);
        }
    }

    @Test
    void dropDeprovisionHardRemovesTheDatabase() throws Exception {
        Backend backend = backend();
        Provisioner provisioner = provisioner();
        provisioner.provision(backend, INSTANCE_DROP, List.of());
        assertThat(provisioner.databaseExists(backend, INSTANCE_DROP)).isTrue();

        provisioner.deprovision(backend, INSTANCE_DROP, DeprovisionMode.DROP);
        assertThat(provisioner.databaseExists(backend, INSTANCE_DROP)).isFalse();
        assertEngineSpecificDropCleanup(backend, INSTANCE_DROP);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    /** Open a connection to the tenant DB using the returned binding credentials. */
    protected Connection connectAs(Map<String, Object> creds) throws SQLException {
        String url = engine().jdbcScheme() + "://" + creds.get("host") + ":" + creds.get("port")
                + "/" + creds.get("database");
        return java.sql.DriverManager.getConnection(url, (String) creds.get("username"),
                (String) creds.get("password"));
    }

    private long countRows(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Names of soft-deleted (parked) databases derived from the original tenant DB name. */
    protected abstract List<String> parkedDatabasesFor(Backend backend, String originalDatabase);

    protected abstract void dropDatabase(Backend backend, String database);

    /** Extra assertions specific to a hard drop (e.g. Postgres owner role removed). */
    protected abstract void assertEngineSpecificDropCleanup(Backend backend, String instanceGuid);
}
