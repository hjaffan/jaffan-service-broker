package com.jaffan.broker.naming;

import java.security.SecureRandom;

/**
 * Generates binding passwords. 32 characters drawn from {@code [A-Za-z0-9]} using {@link SecureRandom}.
 *
 * <p>The alphabet is deliberately restricted to URL-safe <em>alphanumerics</em> (no {@code -} or
 * {@code _}): these passwords are interpolated into {@code CREATE ROLE ... PASSWORD '...'} /
 * {@code CREATE USER ... IDENTIFIED BY '...'} literals which cannot be parameterised, and an
 * alphanumeric-only value can never contain a quote, backslash or other character that would need
 * escaping — so the literal is always safe. They are also safe to embed unencoded in a connection URI.
 */
public final class PasswordGenerator {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int LENGTH = 32;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
