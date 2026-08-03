package com.jaffan.broker.it;

import com.jaffan.broker.backend.Backend;
import com.jaffan.broker.backend.DatabaseEngine;
import com.jaffan.broker.naming.Identifiers;
import com.jaffan.broker.naming.PasswordGenerator;
import com.jaffan.broker.provision.MariaDbProvisioner;
import com.jaffan.broker.provision.Provisioner;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

class MariaDbLifecycleIT extends AbstractLifecycleIT {

    @Container
    static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>(DockerImageName.parse("mariadb:11"))
                    // Ensure the root account is reachable over the mapped port so admin DDL works.
                    .withEnv("MARIADB_ROOT_HOST", "%");

    private static final String ROOT_USER = "root";

    private final Provisioner provisioner = new MariaDbProvisioner(new PasswordGenerator());

    @Override
    protected Backend backend() {
        // Admin pool bound to the 'mysql' maintenance database, connecting as root.
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:mariadb://" + MARIADB.getHost() + ":" + MARIADB.getFirstMappedPort() + "/mysql");
        ds.setUsername(ROOT_USER);
        ds.setPassword(MARIADB.getPassword());
        ds.setMaximumPoolSize(2);
        ds.setAutoCommit(true);
        return new Backend("maria-test", DatabaseEngine.MARIADB, MARIADB.getHost(),
                MARIADB.getFirstMappedPort(), null, ROOT_USER, MARIADB.getPassword(), ds);
    }

    @Override
    protected Provisioner provisioner() {
        return provisioner;
    }

    @Override
    protected List<String> parkedDatabasesFor(Backend backend, String originalDatabase) {
        return backend.jdbc().queryForList(
                "SELECT schema_name FROM information_schema.SCHEMATA WHERE schema_name LIKE ?",
                String.class, "deleted_" + originalDatabase + "_%");
    }

    @Override
    protected void dropDatabase(Backend backend, String database) {
        backend.jdbc().execute("DROP DATABASE IF EXISTS " + Identifiers.quote(database, engine()));
    }

    @Override
    protected void assertEngineSpecificDropCleanup(Backend backend, String instanceGuid) {
        // MariaDB has no per-instance owner role, so a hard drop has no extra objects to remove.
    }
}
