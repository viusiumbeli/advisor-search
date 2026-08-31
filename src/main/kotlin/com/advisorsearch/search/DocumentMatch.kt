package com.advisorsearch.search

import com.advisorsearch.search.expansion.ConceptMatch
import kotlin.time.Duration

data class DocumentMatch(
    val reference: DocumentReference,
    val score: Double,
    val snippet: String,
)

/** One query's floored document rankings, one per retriever, most literal first, each with what it cost. */
internal data class DocumentArms(
    val keyword: List<DocumentMatch>,
    /** What the lexicon's document phrases found and the user's own words did not. */
    val phrase: List<DocumentMatch>,
    val sparse: List<DocumentMatch>,
    val semantic: List<DocumentMatch>,
    val keywordTime: Duration,
    val phraseTime: Duration,
    val sparseTime: Duration,
    val semanticTime: Duration,
    /** The lexicon concepts the query reached, strongest first; empty for most queries. */
    val concepts: List<ConceptMatch>,
    /** Embedding the query and matching it to the lexicon: the only inference a search runs, paid here. */
    val expansionTime: Duration,
)

/** Best match per document across the query's probes, highest score first. */
internal fun Iterable<DocumentMatch>.bestByDocument(): List<DocumentMatch> =
    groupingBy { it.reference.id }
        .reduce { _, best, candidate -> maxOf(best, candidate, compareBy(DocumentMatch::score)) }
        .values
        .sortedWith(compareByDescending<DocumentMatch> { it.score }.thenBy { it.reference.id })
