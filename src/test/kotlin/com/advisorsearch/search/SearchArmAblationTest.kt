package com.advisorsearch.search

import com.advisorsearch.SeededIntegrationTest
import com.advisorsearch.config.SearchProperties
import com.advisorsearch.search.ranking.RankedList
import com.advisorsearch.search.ranking.ReciprocalRankFusion
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
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

    private val configurations =
        listOf(
            "keyword",
            "sparse",
            "semantic",
            "keyword+semantic",
            "keyword+sparse",
            "keyword+sparse+semantic",
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
            val titles = (arms.keyword + arms.sparse + arms.semantic + probed.sparse).associate { it.reference.id to it.reference.title }
            val lists =
                mapOf(
                    "keyword" to arms.keyword,
                    "sparse" to arms.sparse,
                    "semantic" to arms.semantic,
                    "sparse(probes)" to probed.sparse,
                )

            val perConfiguration =
                configurations.associateWith { configuration ->
                    val fused =
                        ReciprocalRankFusion.fuse(
                            configuration.split('+').map { name -> RankedList(name, lists.getValue(name).map { it.reference.id }) },
                            properties.rrfK,
                        )
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

    private companion object {
        const val HIT_AT = 5
    }
}
