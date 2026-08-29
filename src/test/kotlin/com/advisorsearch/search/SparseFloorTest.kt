package com.advisorsearch.search

import com.advisorsearch.SeededIntegrationTest
import com.advisorsearch.config.SearchProperties
import com.advisorsearch.embedding.EmbeddingService
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Guards `search.sparse-floor` and `search.sparse-floor-ratio` from both sides and prints the
 * evidence behind them, the way SemanticFloorTest does for the cosine floors. The scores here are
 * inner products divided by the query's own mass — the average learned weight the best chunk gives
 * the query's terms — which is what makes one absolute number meaningful across queries of
 * different lengths. Full numbers in docs/search-design.md, "Calibrating the cut-offs".
 */
class SparseFloorTest(
    private val documents: DocumentSearchRepository,
    private val embeddings: EmbeddingService,
    private val properties: SearchProperties,
) : SeededIntegrationTest() {
    /** The same answerable set the semantic floor is held to; the sparse arm must not lose any of them. */
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

    @Test
    fun `the floor never silences a document that genuinely answers the query`() {
        val tooLow = mutableListOf<String>()
        var worst = Double.MAX_VALUE

        answerable.forEach { (query, expected) ->
            val ranked = sparse(query)
            val score = ranked.firstOrNull { it.reference.title.contains(expected) }?.score ?: 0.0
            if (score < worst) worst = score
            if (score < properties.sparseFloor) {
                tooLow += "  \"$query\" -> \"$expected\" scored %.4f, below the %.2f floor".format(score, properties.sparseFloor)
            }
            val best = ranked.firstOrNull()?.score ?: 0.0
            println(
                "%-48s expected %.4f  best %.4f  ratio %.2f  above-floor %d  top: %s".format(
                    "\"$query\"",
                    score,
                    best,
                    if (best > 0) score / best else 0.0,
                    ranked.count { it.score >= properties.sparseFloor },
                    ranked
                        .firstOrNull()
                        ?.reference
                        ?.title
                        ?.take(40) ?: "-",
                ),
            )
        }

        println("worst true positive: %.4f against a floor of %.2f".format(worst, properties.sparseFloor))
        assertTrue(tooLow.isEmpty(), "the floor is cutting real answers:\n" + tooLow.joinToString("\n"))
    }

    @Test
    fun `the floor rejects every query the corpus cannot answer`() {
        val leaked = mutableListOf<String>()

        nonsense.forEach { query ->
            val top = sparse(query).firstOrNull()?.score ?: 0.0
            if (top >= properties.sparseFloor) {
                leaked += "  \"$query\" scored %.4f, above the %.2f floor".format(top, properties.sparseFloor)
            }
            println("%-48s %.4f".format("\"$query\"", top))
        }

        assertTrue(leaked.isEmpty(), "the floor is letting nonsense through:\n" + leaked.joinToString("\n"))
    }

    @Test
    fun `an incidental one-term overlap falls under the relative floor`() {
        // The trust deed mentions "plc," — one wordpiece of the policy number — and nothing else of
        // it. The relative floor is what keeps that out of the ranking fusion sees.
        val ranked = sparse("PLC-88213")
        val policy = ranked.first { it.reference.title.contains("Policy Schedule") }.score
        val trustDeed = ranked.first { it.reference.title.contains("Trust Deed") }.score

        println("PLC-88213: policy schedule %.4f, trust deed %.4f, ratio %.2f".format(policy, trustDeed, trustDeed / policy))
        assertTrue(trustDeed < policy * properties.sparseFloorRatio, "the incidental match survives the relative floor")
    }

    /** The bare query, mass normalisation and reduction production uses, with no candidate cap. */
    private fun sparse(query: String): List<DocumentMatch> =
        embeddings
            .encodeQueriesSparsely(listOf(query))
            .filter { !it.isEmpty() }
            .flatMap { vector -> documents.sparseSearch(vector, CANDIDATES).map { it.copy(score = it.score / vector.mass) } }
            .bestByDocument()

    private companion object {
        /** Larger than the corpus, so the report sees every document rather than a shortlist. */
        const val CANDIDATES = 400
    }
}
