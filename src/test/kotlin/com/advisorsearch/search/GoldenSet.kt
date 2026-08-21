package com.advisorsearch.search

/** The parsed `golden-queries.json`: the evaluation cases, split by what they search for. */
data class GoldenSet(
    val documents: List<GoldenQuery>,
    val clients: List<GoldenQuery>,
)
