package org.hhclicker.backend.shared.config;

import liquibase.Liquibase;
import liquibase.integration.spring.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LiquibaseCompatibilityConfiguration {

    @Bean
    Customizer<Liquibase> migrationIdentityCustomizer() {
        return new MigrationIdentityCompatibility();
    }
}
