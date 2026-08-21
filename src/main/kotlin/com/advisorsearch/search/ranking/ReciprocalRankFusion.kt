package com.advisorsearch.search.ranking

import java.util.UUID

/**
 * One retriever's output: the document ids it returned, most relevant first. Only the order carries
 * into fusion — the retriever's own scores are deliberately left behind, because `ts_rank_cd` and
 * cosine similarity are not comparable to each other.
 */
data class RankedList(
    val source: String,
    val ids: List<UUID>,
)

/**
 * A document after fusion: its combined reciprocal-rank weight and which retrievers found it.
 * [sources] is what lets a result explain itself as keyword, semantic or both.
 */
data class FusedDocument(
    val id: UUID,
    val score: Double,
    val sources: Set<String>,
)

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

        // Ties are broken by id so that two runs over the same data return the same order.
        return scores.entries
            .map { (id, score) -> FusedDocument(id, score, sources.getValue(id)) }
            .sortedWith(compareByDescending<FusedDocument> { it.score }.thenBy { it.id })
    }
}
