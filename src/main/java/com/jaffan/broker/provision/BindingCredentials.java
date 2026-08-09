package com.jaffan.broker.provision;

import com.jaffan.broker.backend.Backend;
import com.jaffan.broker.backend.HostPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the credentials JSON returned to the platform on bind. The endpoints are the backend's
 * <em>external</em> hosts so apps connect to the addresses ops intends, which may differ from the
 * ones the broker uses.
 *
 * <p>When the cluster has several nodes, {@code uri} and {@code jdbcUrl} are multi-host URLs pinned
 * to the primary ({@code target_session_attrs=read-write} for libpq-style clients,
 * {@code targetServerType=primary} for JDBC), so apps follow a postgres-ha failover without any
 * load balancer. {@code host}/{@code port} carry the first endpoint for clients that only accept a
 * single address; the full list is in {@code hosts}.
 *
 * <p>This object is never logged; only the resulting map is handed to the OSB library to serialise
 * into the bind response.
 */
public final class BindingCredentials {

    private BindingCredentials() {
    }

    public static Map<String, Object> build(Backend backend, String database, String username,
            String password) {
        List<HostPort> hosts = backend.externalHosts();
        String spec = HostPort.join(hosts);
        boolean multiHost = hosts.size() > 1;
        String scheme = backend.engine().uriScheme();
        String jdbcScheme = backend.engine().jdbcScheme();

        String uri = scheme + "://" + username + ":" + password + "@" + spec + "/" + database
                + (multiHost ? "?target_session_attrs=read-write" : "");
        String jdbcUrl = jdbcScheme + "://" + spec + "/" + database + "?"
                + (multiHost ? "targetServerType=primary&" : "")
                + "user=" + username + "&password=" + password;

        // LinkedHashMap for stable, readable ordering in `cf ... service-key` output.
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("uri", uri);
        credentials.put("jdbcUrl", jdbcUrl);
        credentials.put("host", hosts.get(0).host());
        credentials.put("port", hosts.get(0).port());
        credentials.put("hosts", hosts.stream().map(HostPort::spec).toList());
        credentials.put("database", database);
        credentials.put("username", username);
        credentials.put("password", password);
        return credentials;
    }
}
