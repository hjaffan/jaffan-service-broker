package com.jaffan.broker.naming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PasswordGeneratorTest {

    private final PasswordGenerator generator = new PasswordGenerator();

    @Test
    void generatesThirtyTwoAlphanumericChars() {
        String password = generator.generate();
        assertThat(password).hasSize(32);
        // Alphanumeric-only is what makes it safe to embed in SQL literals and URIs unescaped.
        assertThat(password).matches("[A-Za-z0-9]{32}");
    }

    @Test
    void generatesDistinctValues() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(generator.generate());
        }
        assertThat(seen).hasSize(1000);
    }
}
