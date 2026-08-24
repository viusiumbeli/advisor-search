package com.advisorsearch.search

data class DocumentMatch(
    val reference: DocumentReference,
    val score: Double,
    val snippet: String,
)

/** Best match per document across the query's probes, highest score first. */
internal fun Iterable<DocumentMatch>.bestByDocument(): List<DocumentMatch> =
    groupingBy { it.reference.id }
        .reduce { _, best, candidate -> maxOf(best, candidate, compareBy(DocumentMatch::score)) }
        .values
        .sortedWith(compareByDescending<DocumentMatch> { it.score }.thenBy { it.reference.id })
