package com.advisorsearch

import com.advisorsearch.seed.SeedService
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration tests that need the demo corpus present. Seeding is idempotent, so after the first
 * test class has paid for it the remaining classes only run a handful of existence checks.
 */
abstract class SeededIntegrationTest : IntegrationTest() {
    @Autowired
    private lateinit var seedService: SeedService

    @BeforeEach
    fun seedCorpus() {
        seedService.seed()
    }
}
