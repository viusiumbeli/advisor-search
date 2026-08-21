package com.advisorsearch.search

import java.util.UUID

/**
 * Reciprocal rank fusion (Cormack, Clarke and Buettcher, SIGIR 2009), the default hybrid combiner
 * in Elasticsearch, OpenSearch and Azure AI Search.
 *
 * It combines rankings rather than scores, which is the point: `ts_rank_cd` and cosine similarity
 * are on unrelated scales, and no fixed weighting between them survives a change of corpus. A
 * document ranked by both retrievers outscores one ranked highly by a single retriever, so
 * agreement between keyword and semantic evidence is what wins.
 *
 * Relevance cut-offs must be applied to the input lists, not here: once a score has become a rank,
 * "not similar enough" is no longer expressible.
 */
object ReciprocalRankFusion {
    data class RankedList(
        val source: String,
        val ids: List<UUID>,
    )

    data class Fused(
        val id: UUID,
        val score: Double,
        val sources: Set<String>,
    )

    fun fuse(
        lists: List<RankedList>,
        k: Int,
    ): List<Fused> {
        require(k > 0) { "k must be positive" }
        val scores = mutableMapOf<UUID, Double>()
        val sources = mutableMapOf<UUID, MutableSet<String>>()

        for (list in lists) {
            list.ids.forEachIndexed { index, id ->
                val rank = index + 1
                scores.merge(id, 1.0 / (k + rank), Double::plus)
                sources.getOrPut(id) { linkedSetOf() } += list.source
            }
        }

        // Ties are broken by id so that two runs over the same data return the same order.
        return scores.entries
            .map { (id, score) -> Fused(id, score, sources.getValue(id)) }
            .sortedWith(compareByDescending<Fused> { it.score }.thenBy { it.id })
    }
}
