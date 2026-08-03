package com.jaffan.broker.provision;

import com.jaffan.broker.backend.Backend;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the credentials JSON returned to the platform on bind. Same shape for both engines; only the
 * URI/JDBC schemes differ, and those come from the engine. The host is the backend's
 * <em>external</em> host so apps connect to the address ops intends, which may differ from the one the
 * broker uses.
 *
 * <p>This object is never logged; only the resulting map is handed to the OSB library to serialise
 * into the bind response.
 */
public final class BindingCredentials {

    private BindingCredentials() {
    }

    public static Map<String, Object> build(Backend backend, String database, String username,
            String password) {
        String host = backend.externalHost();
        int port = backend.port();
        String scheme = backend.engine().uriScheme();
        String jdbcScheme = backend.engine().jdbcScheme();

        String uri = scheme + "://" + username + ":" + password + "@" + host + ":" + port + "/" + database;
        String jdbcUrl = jdbcScheme + "://" + host + ":" + port + "/" + database
                + "?user=" + username + "&password=" + password;

        // LinkedHashMap for stable, readable ordering in `cf ... service-key` output.
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("uri", uri);
        credentials.put("jdbcUrl", jdbcUrl);
        credentials.put("host", host);
        credentials.put("port", port);
        credentials.put("database", database);
        credentials.put("username", username);
        credentials.put("password", password);
        return credentials;
    }
}
