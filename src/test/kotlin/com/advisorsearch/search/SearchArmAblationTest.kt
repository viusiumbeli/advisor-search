package com.advisorsearch.search

import com.advisorsearch.SeededIntegrationTest
import com.advisorsearch.config.SearchProperties
import com.advisorsearch.search.ranking.FusedDocument
import com.advisorsearch.search.ranking.RankedList
import com.advisorsearch.search.ranking.ReciprocalRankFusion
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID
import kotlin.test.assertTrue

/**
 * What each arm contributes: the golden document queries fused from every combination of the three
 * floored rankings, with hit@5 and mean reciprocal rank per combination. Reported rather than
 * asserted — except that adding the sparse arm must never lose a query the two-arm system hits.
 * The table is what docs/search-design.md, "Fusion", quotes.
 */
class SearchArmAblationTest(
    private val service: SearchService,
    private val properties: SearchProperties,
    objectMapper: ObjectMapper,
) : SeededIntegrationTest() {
    private val golden: GoldenSet = objectMapper.readValue(ClassPathResource("golden-queries.json").inputStream)

    /**
     * `sparse` is that arm without the lexicon's phrases — what every number published before this
     * change was measured with, so those rows stay comparable — and `sparse(phrases)` is what
     * production runs. The pair is the measurement of what the phrases cost that arm.
     */
    private val configurations =
        listOf(
            "keyword",
            "phrase",
            "sparse",
            "semantic",
            "keyword+semantic",
            "keyword+sparse",
            "keyword+sparse+semantic",
            "keyword+phrase+semantic",
            "keyword+phrase+sparse+semantic",
            "keyword+phrase+sparse(phrases)+semantic",
            "keyword+sparse(probes)+semantic",
        )

    @Test
    fun `the third arm never loses a query the two-arm fusion hits`() {
        val ranks = configurations.associateWith { mutableListOf<Int>() }
        val regressions = mutableListOf<String>()

        println("%-46s %s".format("", configurations.joinToString(" ") { "%-8s".format(it.take(8)) }))
        golden.documents.forEach { case ->
            val arms = service.documentArms(case.query)
            val probed = service.documentArms(case.query, expandSparse = true)
            // The sparse arm without the lexicon's phrases: what every published row below was
            // measured with, so those numbers stay comparable to the ones already in the docs.
            val bare = service.documentArms(case.query, sparsePhrases = false)
            val titles =
                (arms.keyword + arms.phrase + arms.sparse + arms.semantic + probed.sparse + bare.sparse)
                    .associate { it.reference.id to it.reference.title }
            val lists =
                mapOf(
                    "keyword" to arms.keyword,
                    "phrase" to arms.phrase,
                    "sparse" to bare.sparse,
                    "semantic" to arms.semantic,
                    "sparse(phrases)" to arms.sparse,
                    "sparse(probes)" to probed.sparse,
                )

            val perConfiguration =
                configurations.associateWith { configuration ->
                    val fused =
                        ReciprocalRankFusion
                            .fuse(
                                configuration.split('+').map { name -> RankedList(name, lists.getValue(name).map { it.reference.id }) },
                                properties.rrfK,
                            ).sortedWith(productionOrder(titles))
                    fused.indexOfFirst { titles.getValue(it.id).contains(case.expect, ignoreCase = true) } + 1
                }
            perConfiguration.forEach { (configuration, rank) -> ranks.getValue(configuration) += rank }
            val cells =
                configurations.joinToString(" ") {
                    "%-8s".format(
                        perConfiguration.getValue(it).let { r ->
                            if (r >
                                0
                            ) {
                                "$r"
                            } else {
                                "-"
                            }
                        },
                    )
                }
            println("%-46s %s".format("\"${case.query}\"", cells))

            val twoArms = perConfiguration.getValue("keyword+semantic")
            val threeArms = perConfiguration.getValue("keyword+sparse+semantic")
            if (twoArms in 1..HIT_AT &&
                threeArms !in 1..HIT_AT
            ) {
                regressions += "  \"${case.query}\": two arms rank $twoArms, three arms rank $threeArms"
            }
        }

        println()
        configurations.forEach { configuration ->
            val list = ranks.getValue(configuration)
            val hits = list.count { it in 1..HIT_AT }
            val mrr = list.sumOf { if (it > 0) 1.0 / it else 0.0 } / list.size
            println("%-32s hit@%d = %2d/%d   MRR = %.3f".format(configuration, HIT_AT, hits, list.size, mrr))
        }
        assertTrue(regressions.isEmpty(), "the sparse arm lost queries:\n" + regressions.joinToString("\n"))
    }

    /**
     * `fuse` orders exact ties by id and leaves the real tie-break to its caller. SearchService breaks
     * them by agreement, then by the most literal arm, then by title; without the same keys here a
     * semantic-only hit that ties a sparse-only one lands a place above where the product puts it, and
     * the table stops describing the system it is quoted for.
     */
    private fun productionOrder(titles: Map<UUID, String>): Comparator<FusedDocument> =
        compareByDescending<FusedDocument> { it.score }
            .thenByDescending { it.sources.size }
            .thenBy { document ->
                // indexOf returns -1 for a name this table knows and SearchService does not, which
                // would win every tie and quietly sort those rows to the top of a published table.
                document.sources.minOf { source ->
                    EVIDENCE_ORDER.indexOf(source.substringBefore('(')).also { check(it >= 0) { "Unknown arm $source" } }
                }
            }.thenBy { titles.getValue(it.id) }
            .thenBy { it.id }

    private companion object {
        const val HIT_AT = 5

        /** Most to least literal, as SearchService orders sources. */
        val EVIDENCE_ORDER = listOf("keyword", "phrase", "sparse", "semantic")
    }
}
