package com.advisorsearch.search.expansion

import com.advisorsearch.support.WHITESPACE_RUN
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

private val log = LoggerFactory.getLogger(QueryExpander::class.java)
private const val LEXICON = "search/query-expansions.json"

/** Each probe is an extra embedding and an extra scan; the ceiling keeps that bounded. */
private const val MAX_PROBES = 5

/**
 * Supplies what a general-purpose embedding model does not know: which documents serve which purpose
 * in this domain. "Address proof finds a utility bill" is procedural knowledge, not similarity, so it
 * lives in a JSON lexicon a domain expert can edit without touching Kotlin. Expansion widens the
 * semantic arm only. The five-model measurement behind this is in docs/search-design.md, "Why the
 * task's own example needs more than a model".
 */
@Component
class QueryExpander(
    objectMapper: ObjectMapper,
) {
    private val rules: List<ExpansionRule> =
        objectMapper
            .readValue(ClassPathResource(LEXICON).inputStream, ExpansionLexicon::class.java)
            .rules
            .map { rule -> rule.copy(triggers = rule.triggers.map(::normalise)) }

    init {
        log.info("Loaded {} query expansion rules", rules.size)
    }

    /**
     * The probes to run the semantic arm with: the user's own query first, then the expansions of any
     * concept it mentions. A query matching nothing is returned unchanged, so the common case costs
     * one string comparison per rule.
     */
    fun expand(query: String): List<String> {
        val normalised = normalise(query)
        val matched = rules.filter { rule -> rule.triggers.any(normalised::contains) }
        if (matched.isEmpty()) return listOf(query)

        val probes = (listOf(query) + matched.flatMap { it.expansions }).distinct().take(MAX_PROBES)
        log.debug("Expanded '{}' via {} into {} probes", query, matched.map { it.concept }, probes.size)
        return probes
    }

    private fun normalise(text: String): String = text.lowercase().replace(WHITESPACE_RUN, " ").trim()
}
