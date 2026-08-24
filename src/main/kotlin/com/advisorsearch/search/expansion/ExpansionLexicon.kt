package com.advisorsearch.search.expansion

/** The parsed `search/query-expansions.json` document: the whole lexicon as a list of rules. */
data class ExpansionLexicon(
    val rules: List<ExpansionRule> = emptyList(),
)

data class ExpansionRule(
    val concept: String,
    val triggers: List<String>,
    val expansions: List<String>,
)
