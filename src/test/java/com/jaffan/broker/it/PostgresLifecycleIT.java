package com.jaffan.broker.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaffan.broker.backend.Backend;
import com.jaffan.broker.backend.DatabaseEngine;
import com.jaffan.broker.naming.Identifiers;
import com.jaffan.broker.naming.PasswordGenerator;
import com.jaffan.broker.provision.PostgresProvisioner;
import com.jaffan.broker.provision.Provisioner;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

class PostgresLifecycleIT extends AbstractLifecycleIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    private final Provisioner provisioner = new PostgresProvisioner(new PasswordGenerator());

    @Override
    protected Backend backend() {
        // Admin pool bound to the 'postgres' maintenance database; the test user is a superuser.
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/postgres");
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        ds.setMaximumPoolSize(2);
        ds.setAutoCommit(true);
        return new Backend("pg-test", DatabaseEngine.POSTGRES, POSTGRES.getHost(),
                POSTGRES.getFirstMappedPort(), null, POSTGRES.getUsername(), POSTGRES.getPassword(), ds);
    }

    @Override
    protected Provisioner provisioner() {
        return provisioner;
    }

    @Override
    protected List<String> parkedDatabasesFor(Backend backend, String originalDatabase) {
        return backend.jdbc().queryForList(
                "SELECT datname FROM pg_database WHERE datname LIKE ?",
                String.class, "deleted_" + originalDatabase + "_%");
    }

    @Override
    protected void dropDatabase(Backend backend, String database) {
        backend.jdbc().execute("DROP DATABASE IF EXISTS " + Identifiers.quote(database, engine()));
    }

    @Override
    protected void assertEngineSpecificDropCleanup(Backend backend, String instanceGuid) {
        // Hard drop must also remove the per-instance owner role.
        String owner = Identifiers.ownerRole(instanceGuid, DatabaseEngine.POSTGRES);
        List<Integer> rows = backend.jdbc()
                .queryForList("SELECT 1 FROM pg_roles WHERE rolname = ?", Integer.class, owner);
        assertThat(rows).isEmpty();
    }
}
