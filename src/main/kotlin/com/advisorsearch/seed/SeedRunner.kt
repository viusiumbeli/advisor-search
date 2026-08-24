package com.advisorsearch.seed

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Active only under the `seed` profile, which docker compose turns on. Ordered after the startup
 * checks so the model is verified before anything is embedded.
 */
@Component
@Profile("seed")
@Order(1)
class SeedRunner(
    private val seedService: SeedService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        seedService.seed()
    }
}
