package com.claimflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/** Title and description shown in Swagger UI (/swagger-ui.html). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI claimFlowOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ClaimFlow API")
                .version("v1")
                .description("P&C insurance claims: customers, policies, FNOL, claim lifecycle and fraud checks."));
    }
}
