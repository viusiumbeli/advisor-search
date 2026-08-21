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
