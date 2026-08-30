package com.advisorsearch.search.expansion

import com.advisorsearch.IntegrationTest
import com.advisorsearch.embedding.EmbeddingService
import com.advisorsearch.embedding.cosine
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Against the shipped lexicon rather than a fixture: the file is the part a domain expert edits, so
 * what is worth pinning is how the expander treats that file. An integration test now rather than a
 * unit test — matching runs through the dense model, so the expander needs the real encoders behind
 * EmbeddingService. The shared context makes that free, at the price of a Docker-bound test.
 */
class QueryExpanderTest(
    private val expander: QueryExpander,
    private val embeddings: EmbeddingService,
) : IntegrationTest() {
    @Test
    fun `a query about nothing in the lexicon is one probe and no concept`() {
        val expansion = expander.expand("quarterly rebalancing")

        assertEquals(listOf("quarterly rebalancing"), expansion.texts)
        assertTrue(expansion.concepts.isEmpty(), "got ${expansion.concepts}")
    }

    @Test
    fun `a matching query keeps the user's own words first`() {
        val expansion = expander.expand("proof of address")

        assertEquals("proof of address", expansion.texts.first(), "the query itself has to stay the first probe")
        assertTrue(expansion.texts.contains("utility bill"), "got ${expansion.texts}")
        assertEquals(listOf("evidence of address"), expansion.concepts.map { it.concept })
    }

    @Test
    fun `a query naming two concepts carries both into the probes`() {
        // Five probes is the ceiling and the address rule is listed first with four expansions of
        // its own, so taken in lexicon order it fills the budget alone. The identity documents
        // would then be left scoring against the bare query and dropped by the relative floor —
        // with nothing in the response to say that a whole concept had been silenced.
        val expansion = expander.expand("proof of address and proof of identity")

        assertTrue(expansion.texts.size <= 5, "the ceiling still applies: ${expansion.texts}")
        assertTrue(expansion.texts.any { it.contains("utility bill") }, "no address expansion in ${expansion.texts}")
        assertTrue(expansion.texts.any { it.contains("passport") }, "no identity expansion in ${expansion.texts}")
    }

    @Test
    fun `a paraphrase with none of the lexicon's phrasings still reaches its concept`() {
        // No word of this appears in the address rule's phrasings; the substring matcher this
        // replaced could never have reached it.
        val expansion = expander.expand("documents that show where the client lives")

        assertEquals("evidence of address", expansion.concepts.first().concept, "got ${expansion.concepts}")
        assertTrue(expansion.texts.contains("utility bill"), "got ${expansion.texts}")
    }

    @Test
    fun `a query naming one concept does not drag its sibling in`() {
        val expansion = expander.expand("proof of identity")

        assertEquals(listOf("evidence of identity"), expansion.concepts.map { it.concept })
    }

    @Test
    fun `the query's own vector is the first probe`() {
        val expansion = expander.expand("rental yield")

        // Same text through the same encoding path; compared by cosine, never by equals.
        assertTrue(cosine(expansion.probes.first().vector, embeddings.embedQuery("rental yield")) > 0.9999)
    }
}
