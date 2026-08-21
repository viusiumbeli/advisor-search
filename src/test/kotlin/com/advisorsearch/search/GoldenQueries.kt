package com.advisorsearch.search

/** The parsed `golden-queries.json`: the evaluation cases, split by what they search for. */
data class GoldenSet(
    val documents: List<GoldenQuery>,
    val clients: List<GoldenQuery>,
)

/** One evaluation case: the query, the single result that must come back, and why it is interesting. */
data class GoldenQuery(
    val query: String,
    val expect: String,
    val why: String,
)
