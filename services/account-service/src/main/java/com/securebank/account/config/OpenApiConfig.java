package com.securebank.account.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI accountServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SecureBank Account Service API")
                        .version("1.0.0")
                        .description("Educational banking simulator - fictional customers and simulated funds only. "
                                + "Owns accounts, account status and account-level balance state. Customer data is "
                                + "owned by Customer Service; accounting entries by Ledger Service."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
