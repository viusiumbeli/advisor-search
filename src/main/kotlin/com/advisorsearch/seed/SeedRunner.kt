package com.advisorsearch.seed

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Active only under the `seed` profile, which docker compose turns on so that `docker compose up`
 * gives a reviewer a corpus to search immediately. Ordered after the startup checks so the model is
 * already verified before anything is embedded.
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
