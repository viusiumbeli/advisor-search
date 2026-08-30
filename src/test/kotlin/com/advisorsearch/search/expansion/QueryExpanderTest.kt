package com.advisorsearch.search.expansion

import com.advisorsearch.SeededIntegrationTest
import com.advisorsearch.config.SearchProperties
import com.advisorsearch.embedding.EmbeddingService
import com.advisorsearch.embedding.cosine
import com.advisorsearch.search.DocumentMatch
import com.advisorsearch.search.DocumentSearchRepository
import com.advisorsearch.search.GoldenSet
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Against the shipped lexicon rather than a fixture: the file is the part a domain expert edits, so
 * what is worth pinning is how the expander treats that file. An integration test now rather than a
 * unit test — matching runs through the dense model, so the expander needs the real encoders behind
 * EmbeddingService. Seeded, because the two reported tables at the end are read against the demo
 * corpus: what each expansion reaches that the bare concept does not, and the semantic page each
 * expanding query produces. Those tables are the evidence behind every expansion in the JSON and
 * behind docs/search-design.md, "Where the lexicon comes from".
 */
class QueryExpanderTest(
    private val expander: QueryExpander,
    private val embeddings: EmbeddingService,
    private val documents: DocumentSearchRepository,
    private val properties: SearchProperties,
    objectMapper: ObjectMapper,
) : SeededIntegrationTest() {
    private val lexicon: ExpansionLexicon = objectMapper.readValue(ClassPathResource("search/query-expansions.json").inputStream)
    private val golden: GoldenSet = objectMapper.readValue(ClassPathResource("golden-queries.json").inputStream)

    /**
     * Queries outside the golden set whose page is worth seeing every run: a phrasing of the removed
     * rule, the one income phrasing fusion keeps out of the top five, and the source-of-funds rule,
     * which has no seeded document to earn a golden with — the seed holds no completion statement, will
     * or grant of probate — so its page is printed here and its probes are proved in EvidenceFixtureTest.
     */
    private val reportedQueries =
        listOf("death benefit", "proof of income", "source of funds", "evidence of where the invested money came from")

    @Test
    fun `a query about nothing in the lexicon is one probe and no concept`() {
        val expansion = expander.expand("quarterly rebalancing")

        assertEquals(listOf("quarterly rebalancing"), expansion.texts)
        assertTrue(expansion.concepts.isEmpty(), "got ${expansion.concepts}")
    }

    @Test
    fun `a phrasing of the removed death-benefit rule is one probe and no concept`() {
        // "life cover paid on death" shipped in the first lexicon and was removed: the policy schedule
        // is the top result for its phrasings on meaning alone, and its probes only raised the relative
        // floor against the two pension documents that also state a death benefit.
        val expansion = expander.expand("death benefit")

        assertEquals(listOf("death benefit"), expansion.texts)
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
    fun `a query naming one concept runs its own words and the rule's first four expansions, in order`() {
        // Five probes is the ceiling and the query is always the first, so a rule's fifth expansion
        // never runs for anyone: list order in the JSON is the budget, and the reach table below is
        // what that order was read from.
        lexicon.rules.forEach { rule ->
            assertEquals(
                listOf(rule.concept) + rule.expansions.take(EXPANSIONS_PER_CONCEPT),
                expander.expand(rule.concept).texts,
                rule.concept,
            )
        }
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

    /**
     * For every rule: the documents its bare concept reaches above the semantic floor, then for each
     * expansion its top three documents and the documents it adds that the bare concept did not. An
     * expansion that adds nothing for this corpus is still legitimate — the seeded corpus holds no
     * payslip — but it goes after the ones that do, because a two-concept query runs only two per rule.
     */
    @Test
    fun `what each expansion reaches in the seeded corpus, reported`() {
        lexicon.rules.forEach { rule ->
            val texts = listOf(rule.concept) + rule.expansions
            val reached = texts.zip(embeddings.embedQueries(texts)) { text, vector -> text to documents.semanticSearch(vector, CANDIDATES) }
            val bare = reached.first().second.aboveTheFloor()
            println("--- ${rule.concept}: bare concept reaches $bare")
            reached.drop(1).forEach { (text, matches) ->
                val top = matches.take(3).joinToString(", ") { "%s %.3f".format(it.reference.title, it.score) }
                val adds = matches.aboveTheFloor() - bare
                println("%-52s top: %s | adds: %s".format("'$text'", top, adds.ifEmpty { setOf("nothing") }))
            }
        }
    }

    /**
     * The semantic arm's page — every probe's matches, best per document, under the absolute and
     * relative floors — for each rule's concept and every golden document query that expands, with the
     * probe that carried each document and the bare query's own score for it. What the docs' "which
     * documents a probe brings in" claims are read from, and where a title-shaped probe that raises the
     * ceiling against the real answer shows up.
     */
    @Test
    fun `the semantic page each expanding query produces, reported`() {
        val expanding = golden.documents.map { it.query }.filter { expander.expand(it).concepts.isNotEmpty() }
        println("${expanding.size} of ${golden.documents.size} golden document queries expand")

        (lexicon.rules.map { it.concept } + expanding + reportedQueries).distinct().forEach(::printPage)
    }

    private fun printPage(query: String) {
        val expansion = expander.expand(query)
        val best = mutableMapOf<UUID, Pair<DocumentMatch, String>>()
        val bare = mutableMapOf<UUID, Double>()
        expansion.probes.forEachIndexed { index, probe ->
            documents.semanticSearch(probe.vector, CANDIDATES).forEach { match ->
                if (index == 0) bare[match.reference.id] = match.score
                val current = best[match.reference.id]
                if (current == null || match.score > current.first.score) best[match.reference.id] = match to probe.text
            }
        }
        val ranked = best.values.sortedByDescending { it.first.score }
        val floor = maxOf(properties.semanticFloor, (ranked.firstOrNull()?.first?.score ?: 0.0) * properties.semanticFloorRatio)
        val page = ranked.filter { it.first.score >= floor }
        println(
            "--- \"%s\" concepts=%s probes=%d floor %.3f page %d".format(
                query,
                expansion.concepts.map { it.concept },
                expansion.texts.size,
                floor,
                page.size,
            ),
        )
        page.forEachIndexed { index, (match, probe) ->
            val own = bare[match.reference.id]?.let { "%.3f".format(it) } ?: "-"
            println("  %2d. %-58s %.3f via '%s' (bare query %s)".format(index + 1, match.reference.title, match.score, probe, own))
        }
    }

    private fun List<DocumentMatch>.aboveTheFloor(): Set<String> =
        filter {
            it.score >= properties.semanticFloor
        }.map { it.reference.title }.toSet()

    private companion object {
        /** The query is always the first of five probes. */
        const val EXPANSIONS_PER_CONCEPT = 4

        /** Larger than the corpus, so the tables see every document rather than a shortlist. */
        const val CANDIDATES = 400
    }
}
