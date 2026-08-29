package com.advisorsearch.search

import kotlin.time.Duration

data class DocumentMatch(
    val reference: DocumentReference,
    val score: Double,
    val snippet: String,
)

/** One query's floored document rankings, one per retriever, most literal first, each with what it cost. */
internal data class DocumentArms(
    val keyword: List<DocumentMatch>,
    val sparse: List<DocumentMatch>,
    val semantic: List<DocumentMatch>,
    val keywordTime: Duration,
    val sparseTime: Duration,
    val semanticTime: Duration,
)

/** Best match per document across the query's probes, highest score first. */
internal fun Iterable<DocumentMatch>.bestByDocument(): List<DocumentMatch> =
    groupingBy { it.reference.id }
        .reduce { _, best, candidate -> maxOf(best, candidate, compareBy(DocumentMatch::score)) }
        .values
        .sortedWith(compareByDescending<DocumentMatch> { it.score }.thenBy { it.reference.id })
