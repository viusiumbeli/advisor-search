package com.advisorsearch.search.expansion

/** The parsed `search/query-expansions.json` document: the whole lexicon as a list of rules. */
data class ExpansionLexicon(
    val rules: List<ExpansionRule> = emptyList(),
)

data class ExpansionRule(
    val concept: String,
    /** Example phrasings of the requirement, matched by embedding similarity rather than containment; the concept name is always one too. */
    val paraphrases: List<String>,
    val expansions: List<String>,
)
