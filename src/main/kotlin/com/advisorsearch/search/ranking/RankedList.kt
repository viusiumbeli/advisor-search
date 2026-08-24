package com.advisorsearch.search.ranking

import java.util.UUID

/** One retriever's output, most relevant first. Scores are left behind; only the order fuses. */
data class RankedList(
    val source: String,
    val ids: List<UUID>,
)
