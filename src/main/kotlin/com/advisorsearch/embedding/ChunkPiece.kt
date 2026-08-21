package com.advisorsearch.embedding

/**
 * An implementation detail of [Chunker]: one indivisible span of text plus the whitespace that
 * preceded it, so a rendered chunk reproduces the document exactly. `internal` rather than private
 * only because it now lives in its own file.
 */
internal data class ChunkPiece(
    val text: String,
    val separator: String,
    val tokens: Int,
)
