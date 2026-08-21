package com.advisorsearch

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Postgres for the integration tests, wired in by Spring Boot's service connection support so no
 * datasource properties have to be set by hand.
 *
 * Nearly every test class shares this exact configuration, so Spring caches one application context
 * and reuses one container across them. Only a class that changes properties — the API key test —
 * gets its own.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestDatabase {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer =
        PostgreSQLContainer(
            // pgvector's image is Postgres plus the extension; Testcontainers needs telling that it
            // can stand in for the official one.
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"),
        )
}

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase::class)
@ActiveProfiles("test")
abstract class IntegrationTest
