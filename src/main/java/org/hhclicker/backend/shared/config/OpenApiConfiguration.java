package org.hhclicker.backend.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI hhClickerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("HH Clicker API")
                .version("v1")
                .description("Локальный API управления поисками вакансий и автоматическими откликами."));
    }
}

