package com.advisorsearch.search.expansion

/**
 * One concept from the expansion lexicon: the phrases that mean an advisor is asking for it, and
 * the phrases worth searching for as well. Loaded from `search/query-expansions.json`.
 */
data class ExpansionRule(
    val concept: String,
    val triggers: List<String>,
    val expansions: List<String>,
)
