package com.advisorsearch.search.expansion

/** The parsed `search/query-expansions.json` document: the whole lexicon as a list of rules. */
data class ExpansionLexicon(
    val rules: List<ExpansionRule> = emptyList(),
)

/**
 * One requirement and the two kinds of knowledge about it, split by which arm can use each. A
 * document type is what an advisor calls the document; a document phrase is what the document says.
 * The split is not cosmetic: a document never states its own type, so a type name is worthless to
 * the arms that match exact tokens, while a phrase from inside the document is exactly what they
 * want and costs no vector scan.
 */
data class ExpansionRule(
    val concept: String,
    /** Example phrasings of the requirement, matched by embedding similarity rather than containment; the concept name is always one too. */
    val paraphrases: List<String>,
    /**
     * What an advisor calls the document — "utility bill". Embedded at startup and run as semantic
     * probes, the first four of them per query. No default: a lexicon that predates the split should
     * fail at startup rather than come up silently with nothing to expand.
     */
    val documentTypes: List<String>,
    /**
     * Text the document itself carries, in its own words — "supply address", "gross pay". Never
     * embedded: it goes to the lexical and sparse arms, where such a phrase is the exact token they
     * exist to match. What may appear here is gated by `EvidenceFixtureTest`.
     */
    val documentPhrases: List<String> = emptyList(),
)
