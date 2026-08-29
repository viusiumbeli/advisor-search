package com.advisorsearch.search

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Tag(name = "Search")
class SearchController(
    private val service: SearchService,
) {
    @GetMapping("/search")
    @Operation(
        summary = "Search across clients and documents",
        description =
            "Returns one array in two blocks: client hits first, then document hits. Scores are " +
                "comparable within a block but not between blocks — client hits are trigram " +
                "similarities in 0..1, document hits are reciprocal-rank-fusion weights. For a " +
                "document, matched_on is keyword, sparse or semantic, or multiple when more than one " +
                "retriever agreed; sources lists which, most literal first.",
    )
    fun search(
        // Built-in method validation renders a blank q as a problem+json 400; a nullable limit is
        // already optional, no `required = false` needed.
        @RequestParam @NotBlank q: String,
        @RequestParam limit: Int?,
    ): List<SearchHit> = service.search(q, limit)
}
