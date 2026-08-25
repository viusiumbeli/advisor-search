package com.advisorsearch.embedding

/** A span of text plus the whitespace before it, so a rendered chunk reproduces the document exactly. */
internal data class ChunkPiece(
    val text: String,
    val separator: String,
    val tokens: Int,
)

/** Half-open `[start, end)` over the document, which is how the chunker decides where pieces are. */
internal data class ChunkSpan(
    val start: Int,
    val end: Int,
)
