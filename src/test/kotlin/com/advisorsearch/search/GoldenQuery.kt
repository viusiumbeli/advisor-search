package com.advisorsearch.search

/** One evaluation case: the query, the single result that must come back, and why it is interesting. */
data class GoldenQuery(
    val query: String,
    val expect: String,
    val why: String,
)
