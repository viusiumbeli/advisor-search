package com.advisorsearch

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestConstructor
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Postgres for the integration tests, wired in by service connection support so no datasource
 * properties are set by hand. Nearly every class shares this configuration, so Spring caches one
 * context and reuses one container; only a class that changes properties gets its own.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestDatabase {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer =
        PostgreSQLContainer(
            // pgvector's image is Postgres plus the extension; Testcontainers needs telling that it
            // can stand in for the official one.
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg18").asCompatibleSubstituteFor("postgres"),
        )
}

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase::class)
@ActiveProfiles("test")
// Inherited by every subclass: constructor parameters are autowired without nine per-class
// @Autowired annotations (and without the extra indentation level they force on Kotlin classes).
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
abstract class IntegrationTest
