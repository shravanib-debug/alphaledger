package com.alphaledger.common.autoconfigure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** OpenAPI metadata with a bearer-token scheme, so Swagger UI's "Authorize" button accepts an access token. */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
public class OpenApiAutoConfiguration {

    private static final String BEARER = "bearerAuth";

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI alphaLedgerOpenApi(@Value("${spring.application.name:alphaledger}") String applicationName) {
        return new OpenAPI()
                .info(new Info().title("AlphaLedger " + applicationName).version("v1")
                        .description("Get a token from POST /api/v1/auth/login, then use Authorize."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
