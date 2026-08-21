package com.advisorsearch.search.expansion

/** The parsed `search/query-expansions.json` document: the whole lexicon as a list of rules. */
data class ExpansionLexicon(
    val rules: List<ExpansionRule> = emptyList(),
)

/**
 * One concept from the lexicon: the phrases that mean an advisor is asking for it, and the phrases
 * worth searching for as well.
 */
data class ExpansionRule(
    val concept: String,
    val triggers: List<String>,
    val expansions: List<String>,
)
