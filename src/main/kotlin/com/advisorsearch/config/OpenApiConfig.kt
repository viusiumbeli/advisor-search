package com.advisorsearch.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val API_KEY = "ApiKey"

@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Advisor Search API")
                    .version("1.0.0")
                    .description(
                        "Search across clients and their documents. Clients are matched with " +
                            "trigrams over name, email and description; documents are matched by " +
                            "full-text search, by learned sparse term weights (SPLADE) and by " +
                            "embedding similarity, combined with reciprocal rank fusion. The " +
                            "deployed instance starts empty; the one " +
                            "operation under Demo loads a corpus into an empty one so the examples " +
                            "in the README have something to find.",
                    ),
            ).components(
                Components().addSecuritySchemes(
                    API_KEY,
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name("X-API-Key")
                        .description("Only required on the deployed instance; local runs need no key."),
                ),
            ).addSecurityItem(SecurityRequirement().addList(API_KEY))
}
