package com.advisorsearch.search.expansion

import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Against the shipped lexicon rather than a fixture: the file is the part a domain expert edits, so
 * what is worth pinning is how the expander treats that file.
 */
class QueryExpanderTest {
    private val expander = QueryExpander(jacksonObjectMapper())

    @Test
    fun `a query matching no concept is left exactly as it was`() {
        assertEquals(listOf("quarterly rebalancing"), expander.expand("quarterly rebalancing"))
    }

    @Test
    fun `a matching query keeps the user's own words first`() {
        val probes = expander.expand("proof of address")

        assertEquals("proof of address", probes.first(), "the query itself has to stay the first probe")
        assertTrue(probes.contains("utility bill"), "got $probes")
    }

    @Test
    fun `a query naming two concepts carries both into the probes`() {
        // Five probes is the ceiling and the address rule is listed first with four expansions of
        // its own, so taken in lexicon order it fills the budget alone. The identity documents
        // would then be left scoring against the bare query and dropped by the relative floor —
        // with nothing in the response to say that a whole concept had been silenced.
        val probes = expander.expand("proof of address and proof of identity")

        assertTrue(probes.size <= 5, "the ceiling still applies: $probes")
        assertTrue(probes.any { it.contains("utility bill") }, "no address expansion in $probes")
        assertTrue(probes.any { it.contains("passport") }, "no identity expansion in $probes")
    }
}
