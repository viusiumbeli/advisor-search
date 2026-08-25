package com.advisorsearch.seed

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The demo corpus as something a reader can ask for, because the deployed instance starts empty and
 * the examples in the README and on the console page are about documents it does not have.
 */
@RestController
@Tag(name = "Demo")
class DemoCorpusController(
    private val seedService: SeedService,
) {
    @PostMapping("/demo-corpus")
    @Operation(
        summary = "Load the demo corpus into an empty instance",
        description =
            "Loads the ten clients and twenty documents the README describes, through the same POST path " +
                "any other ingest uses, so everything is chunked and embedded the same way. Answers 409 if " +
                "the instance already holds data, because a second corpus competes with the first in search " +
                "results. Synchronous, and it embeds twenty documents, so it takes several seconds.",
    )
    fun load(): ResponseEntity<SeedSummary> = ResponseEntity.status(HttpStatus.CREATED).body(seedService.seedEmptyCorpus())
}
