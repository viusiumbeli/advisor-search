package com.advisorsearch.search

import com.advisorsearch.SeededIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertTrue

/**
 * The evaluation harness. Every query in golden-queries.json must return its expected result inside
 * the top five, and the mean reciprocal rank is printed so a change that keeps every query passing
 * while pushing results down the list is still visible.
 *
 * This is what stops a relevance change from being judged by whether one hand-run example still
 * looks right.
 */
class SearchQualityTest(
    private val service: SearchService,
    objectMapper: ObjectMapper,
) : SeededIntegrationTest() {
    data class GoldenQuery(
        val query: String,
        val expect: String,
        val why: String,
    )

    data class GoldenSet(
        val documents: List<GoldenQuery>,
        val clients: List<GoldenQuery>,
    )

    private val golden: GoldenSet = objectMapper.readValue(ClassPathResource("golden-queries.json").inputStream)

    @Test
    fun `every golden document query returns its document in the top five`() {
        val misses = mutableListOf<String>()
        var reciprocalRankSum = 0.0

        golden.documents.forEach { case ->
            val hits = service.search(case.query, 10).filterIsInstance<DocumentHit>()
            val rank = hits.indexOfFirst { it.document.title.contains(case.expect, ignoreCase = true) } + 1
            if (rank in 1..HIT_AT) reciprocalRankSum += 1.0 / rank else misses += format(case, rank, hits.size)
            println("%-46s rank %-3s %s".format("\"${case.query}\"", if (rank > 0) "$rank" else "-", case.expect))
        }

        println(
            "documents: hit@%d = %d/%d, MRR = %.3f".format(
                HIT_AT,
                golden.documents.size - misses.size,
                golden.documents.size,
                reciprocalRankSum / golden.documents.size,
            ),
        )
        assertTrue(misses.isEmpty(), "golden document queries failed:\n" + misses.joinToString("\n"))
    }

    @Test
    fun `every golden client query returns its client in the top five`() {
        val misses = mutableListOf<String>()
        var reciprocalRankSum = 0.0

        golden.clients.forEach { case ->
            val hits = service.search(case.query, 10).filterIsInstance<ClientHit>()
            val rank = hits.indexOfFirst { it.client.email.equals(case.expect, ignoreCase = true) } + 1
            if (rank in 1..HIT_AT) reciprocalRankSum += 1.0 / rank else misses += format(case, rank, hits.size)
            println("%-46s rank %-3s %s".format("\"${case.query}\"", if (rank > 0) "$rank" else "-", case.expect))
        }

        println(
            "clients: hit@%d = %d/%d, MRR = %.3f".format(
                HIT_AT,
                golden.clients.size - misses.size,
                golden.clients.size,
                reciprocalRankSum / golden.clients.size,
            ),
        )
        assertTrue(misses.isEmpty(), "golden client queries failed:\n" + misses.joinToString("\n"))
    }

    @Test
    fun `a query with no plausible answer returns nothing`() {
        val hits = service.search("photosynthesis in tropical rainforest canopies", 10)

        assertTrue(hits.isEmpty(), "expected no hits, got ${hits.map { it.matchedOn }}")
    }

    private fun format(
        case: GoldenQuery,
        rank: Int,
        returned: Int,
    ): String =
        "  \"${case.query}\" -> expected \"${case.expect}\" " +
            "(${case.why}); rank=${if (rank > 0) rank else "not returned"} of $returned"

    private companion object {
        const val HIT_AT = 5
    }
}
