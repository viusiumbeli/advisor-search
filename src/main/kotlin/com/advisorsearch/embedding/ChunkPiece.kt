package com.advisorsearch.embedding

/**
 * One indivisible span of text plus the whitespace that preceded it, so a rendered chunk reproduces
 * the document exactly. An implementation detail of [Chunker] — `internal` only because a top-level
 * `private` would be invisible to the chunker's own file.
 */
internal data class ChunkPiece(
    val text: String,
    val separator: String,
    val tokens: Int,
)
