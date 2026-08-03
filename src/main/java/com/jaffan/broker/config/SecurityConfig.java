package com.jaffan.broker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic auth for the broker.
 *
 * <ul>
 *   <li>{@code /v2/**} (the OSB API Cloud Controller calls) requires the {@code BROKER_USER} /
 *       {@code BROKER_PASSWORD} credentials.</li>
 *   <li>{@code /actuator/health} is open, so the CF platform health check can poll it unauthenticated.</li>
 * </ul>
 *
 * Sessions are stateless and CSRF is disabled — every request is an independent, Basic-authenticated
 * API call from Cloud Controller, never a browser form.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/v2/**").authenticated()
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsService(Environment env, PasswordEncoder encoder) {
        String username = require(env, "BROKER_USER");
        String password = require(env, "BROKER_PASSWORD");
        UserDetails broker = User.withUsername(username)
                .password(encoder.encode(password))
                .roles("BROKER")
                .build();
        return new InMemoryUserDetailsManager(broker);
    }

    private String require(Environment env, String name) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required environment variable is not set: " + name);
        }
        return value;
    }
}
