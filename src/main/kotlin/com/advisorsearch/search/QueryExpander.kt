package com.advisorsearch.search

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
 * Supplies the one thing a general-purpose embedding model does not know: which documents serve
 * which purpose in this domain.
 *
 * The task's own example — "address proof" should find a utility bill — is not a similarity
 * relationship. It is procedural knowledge. Five embedding models were measured on this corpus and
 * every one of them ranked the utility bill 13th or worse for that query, while ranking probes like
 * "retirement income planning" or "share options vesting" first. Adding a bigger model does not
 * close that gap, so the knowledge is stated here instead, in a file a domain expert can edit
 * without touching Kotlin.
 *
 * Expansion widens the semantic arm only. The lexical arm's value is precision on exact tokens, and
 * OR-ing extra phrases into it would buy recall the semantic arm already delivers, at the cost of
 * matching every document that merely mentions a bank statement.
 */
@Component
class QueryExpander(
    objectMapper: ObjectMapper,
) {
    data class Lexicon(
        val rules: List<Rule> = emptyList(),
    )

    data class Rule(
        val concept: String,
        val triggers: List<String>,
        val expansions: List<String>,
    )

    private val rules: List<Rule> =
        objectMapper
            .readValue(ClassPathResource(LEXICON).inputStream, Lexicon::class.java)
            .rules
            .map { rule -> rule.copy(triggers = rule.triggers.map(::normalise)) }

    init {
        log.info("Loaded {} query expansion rules", rules.size)
    }

    /**
     * Returns the probes to run the semantic arm with: always the user's own query first, then the
     * expansions of any concept it mentions. A query that matches nothing is returned unchanged, so
     * the common case costs one string comparison per rule and nothing else.
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
