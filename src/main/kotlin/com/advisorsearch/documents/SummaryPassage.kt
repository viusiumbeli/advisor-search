package com.advisorsearch.documents

/**
 * One passage of an extractive summary: a chunk verbatim from the document, its position in reading
 * order, and how close it sits to the document's own embedding centroid.
 */
data class SummaryPassage(
    val chunkIndex: Int,
    val text: String,
    val centrality: Double,
)
