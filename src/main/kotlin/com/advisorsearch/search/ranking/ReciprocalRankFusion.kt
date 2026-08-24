package com.advisorsearch.search.ranking

import java.util.UUID

/**
 * Reciprocal rank fusion (Cormack, Clarke and Buettcher, SIGIR 2009). Combines rankings, not scores,
 * so callers must apply relevance cut-offs to the input lists — a rank cannot express "not similar
 * enough". Why this and not a weighted blend: docs/search-design.md, "Fusion".
 */
object ReciprocalRankFusion {
    fun fuse(
        lists: List<RankedList>,
        k: Int,
    ): List<FusedDocument> {
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

        // Id breaks ties only so repeated runs agree; callers re-sort on their own keys.
        return scores.entries
            .map { (id, score) -> FusedDocument(id, score, sources.getValue(id)) }
            .sortedWith(compareByDescending<FusedDocument> { it.score }.thenBy { it.id })
    }
}
