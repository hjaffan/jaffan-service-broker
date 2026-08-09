package com.jaffan.broker.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class HostPortTest {

    @Test
    void singleHostWithoutPortGetsTheDefault() {
        assertThat(HostPort.parseList("pg.example.com", 5432))
                .containsExactly(new HostPort("pg.example.com", 5432));
    }

    @Test
    void parsesACommaSeparatedClusterListWithMixedPorts() {
        List<HostPort> hosts = HostPort.parseList("10.0.1.10:5432, 10.0.1.11,10.0.1.12:6432", 5432);
        assertThat(hosts).containsExactly(
                new HostPort("10.0.1.10", 5432),
                new HostPort("10.0.1.11", 5432),
                new HostPort("10.0.1.12", 6432));
        assertThat(HostPort.join(hosts)).isEqualTo("10.0.1.10:5432,10.0.1.11:5432,10.0.1.12:6432");
    }

    @Test
    void rejectsEmptyInputAndMalformedPorts() {
        assertThatThrownBy(() -> HostPort.parseList("", 5432))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostPort.parseList(" , ", 5432))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostPort.parseList("host:notaport", 5432))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostPort.parseList("host:70000", 5432))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
