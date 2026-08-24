package com.advisorsearch.search

import com.advisorsearch.SeededIntegrationTest
import com.advisorsearch.config.SearchProperties
import com.advisorsearch.embedding.EmbeddingService
import com.advisorsearch.search.expansion.QueryExpander
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Guards `search.semantic-floor` from both sides and prints the evidence behind it. The floor stops a
 * query with no answer returning a page of vague scores; it cannot also judge relevance, because the
 * true- and false-positive ranges overlap. Full numbers in docs/search-design.md, "Calibrating the
 * cut-offs".
 */
class SemanticFloorTest(
    private val documents: DocumentSearchRepository,
    private val embeddings: EmbeddingService,
    private val expander: QueryExpander,
    private val properties: SearchProperties,
) : SeededIntegrationTest() {
    private val answerable =
        mapOf(
            "address proof" to "Electricity Account",
            "proof of address" to "Electricity Account",
            "utility bill" to "Electricity Account",
            "who can act for a client if they lose capacity" to "Lasting Power",
            "power of attorney" to "Lasting Power",
            "retirement income planning" to "Suitability Report",
            "drawdown sustainability" to "Suitability Report",
            "fund manager left the company" to "Annual Investment Review",
            "inheritance tax on gifts" to "Meeting Notes",
            "rental yield" to "Buy-to-Let",
            "share options vesting" to "EMI Share Option",
            "anti money laundering checks" to "Onboarding Checklist",
            "capital gains on a second home" to "Capital Gains",
            "trustee duties" to "Trust Deed",
            "double taxation treaty" to "CRS Tax Residency",
            "energy performance certificate" to "Buy-to-Let",
            "break clause" to "Assured Shorthold",
            "ISA transfer" to "ISA Transfer",
        )

    /** Queries about nothing in the corpus. Whatever they score, the floor must reject it. */
    private val nonsense =
        listOf(
            "zzzqqq nonsense token",
            "photosynthesis in tropical rainforest canopies",
            "the offside rule in association football",
        )

    /**
     * Proper nouns and reference codes: the lexical arm answers these, and the semantic arm's best
     * guess lands inside the true-positive range. Reported, not asserted.
     */
    private val lexical = listOf("AldgateWealth", "raghunathan", "PLC-88213")

    @Test
    fun `the floor never silences a document that genuinely answers the query`() {
        val tooLow = mutableListOf<String>()
        var worst = 1.0

        answerable.forEach { (query, expected) ->
            val score = bestScoreFor(query, expected)
            if (score < worst) worst = score
            if (score < properties.semanticFloor) {
                tooLow +=
                    "  \"$query\" -> \"$expected\" scored %.4f, below the %.2f floor"
                        .format(score, properties.semanticFloor)
            }
            val ranked = semantic(query)
            val best = ranked.firstOrNull()?.score ?: 0.0
            println(
                "%-48s expected %.4f  best %.4f  ratio %.2f  above-0.30 %d".format(
                    "\"$query\"",
                    score,
                    best,
                    if (best > 0) score / best else 0.0,
                    ranked.count { it.score >= 0.30 },
                ),
            )
        }

        println("worst true positive: %.4f against a floor of %.2f".format(worst, properties.semanticFloor))
        assertTrue(tooLow.isEmpty(), "the floor is cutting real answers:\n" + tooLow.joinToString("\n"))
    }

    @Test
    fun `the floor rejects every query the corpus cannot answer`() {
        val leaked = mutableListOf<String>()

        nonsense.forEach { query ->
            val top = topScore(query)
            if (top >= properties.semanticFloor) {
                leaked += "  \"$query\" scored %.4f, above the %.2f floor".format(top, properties.semanticFloor)
            }
            println("%-48s %.4f".format("\"$query\"", top))
        }

        println("--- reported only: queries the lexical arm owns ---")
        lexical.forEach { println("%-48s %.4f".format("\"$it\"", topScore(it))) }

        assertTrue(leaked.isEmpty(), "the floor is letting nonsense through:\n" + leaked.joinToString("\n"))
    }

    private fun bestScoreFor(
        query: String,
        expectedTitle: String,
    ): Double =
        semantic(query)
            .firstOrNull { it.reference.title.contains(expectedTitle) }
            ?.score
            ?: 0.0

    private fun topScore(query: String): Double = semantic(query).firstOrNull()?.score ?: 0.0

    /** Same probes and the same shared reduction production uses, but with no candidate cap. */
    private fun semantic(query: String): List<DocumentMatch> =
        embeddings
            .embedQueries(expander.expand(query))
            .flatMap { vector -> documents.semanticSearch(vector, CANDIDATES) }
            .bestByDocument()

    private companion object {
        /** Larger than the corpus, so the report sees every document rather than a shortlist. */
        const val CANDIDATES = 400
    }
}
