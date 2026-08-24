package com.jaffan.broker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * Entry point for the jaffan-db-broker.
 *
 * <p>{@link DataSourceAutoConfiguration} is excluded because this broker owns no database of its own —
 * it builds one small admin pool per backend by hand (see {@code BackendConfig}) and must never try to
 * auto-configure a primary {@code spring.datasource}.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class JaffanDbBrokerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JaffanDbBrokerApplication.class, args);
    }
}
