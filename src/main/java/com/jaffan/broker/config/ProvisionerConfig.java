package com.jaffan.broker.config;

import com.jaffan.broker.naming.PasswordGenerator;
import com.jaffan.broker.provision.MariaDbProvisioner;
import com.jaffan.broker.provision.PostgresProvisioner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the stateless collaborators: the password generator and one provisioner per engine. Both
 * provisioners are picked up by {@code BackendRouter} as a {@code List<Provisioner>}.
 */
@Configuration
public class ProvisionerConfig {

    @Bean
    public PasswordGenerator passwordGenerator() {
        return new PasswordGenerator();
    }

    @Bean
    public PostgresProvisioner postgresProvisioner(PasswordGenerator passwordGenerator) {
        return new PostgresProvisioner(passwordGenerator);
    }

    @Bean
    public MariaDbProvisioner mariaDbProvisioner(PasswordGenerator passwordGenerator) {
        return new MariaDbProvisioner(passwordGenerator);
    }
}
