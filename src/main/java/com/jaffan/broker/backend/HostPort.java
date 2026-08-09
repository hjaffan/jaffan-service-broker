package com.jaffan.broker.backend;

import java.util.ArrayList;
import java.util.List;

/**
 * One {@code host:port} endpoint of the backing cluster. A postgres-ha deployment has several nodes;
 * {@link #parseList} turns the comma-separated {@code PG_HOST} value ({@code host[:port],...}) into
 * an ordered endpoint list that JDBC/libpq multi-host URLs are built from.
 */
public record HostPort(String host, int port) {

    public HostPort {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        host = host.trim();
    }

    /** {@code host:port}, the form used inside connection URLs. */
    public String spec() {
        return host + ":" + port;
    }

    /** Comma-joined {@code host:port} list, the multi-host section of a JDBC/libpq URL. */
    public static String join(List<HostPort> hosts) {
        return String.join(",", hosts.stream().map(HostPort::spec).toList());
    }

    /**
     * Parse a comma-separated {@code host[:port]} list; entries without an explicit port get
     * {@code defaultPort}. Rejects empty input and malformed ports rather than guessing.
     */
    public static List<HostPort> parseList(String raw, int defaultPort) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("host list must not be empty");
        }
        List<HostPort> hosts = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.lastIndexOf(':');
            if (colon < 0) {
                hosts.add(new HostPort(trimmed, defaultPort));
            } else {
                String host = trimmed.substring(0, colon);
                String portRaw = trimmed.substring(colon + 1);
                try {
                    hosts.add(new HostPort(host, Integer.parseInt(portRaw)));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid port in host entry: " + trimmed);
                }
            }
        }
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("host list must contain at least one host");
        }
        return List.copyOf(hosts);
    }
}
