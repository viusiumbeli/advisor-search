package com.advisorsearch.search

/** A document hit from one retriever: the document, its score on that arm, and its snippet. */
data class DocumentMatch(
    val reference: DocumentReference,
    val score: Double,
    val snippet: String,
)

/**
 * Best match per document across several ranked lists (one per query probe), highest score first.
 * Shared by the search service and the calibration test so the test exercises the same reduction
 * production uses instead of re-implementing it.
 */
internal fun Iterable<DocumentMatch>.bestByDocument(): List<DocumentMatch> =
    groupingBy { it.reference.id }
        .reduce { _, best, candidate -> maxOf(best, candidate, compareBy(DocumentMatch::score)) }
        .values
        .sortedWith(compareByDescending<DocumentMatch> { it.score }.thenBy { it.reference.id })
