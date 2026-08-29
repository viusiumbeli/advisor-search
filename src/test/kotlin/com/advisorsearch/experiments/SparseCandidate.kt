package com.advisorsearch.experiments

/**
 * A sparse checkpoint under comparison, with the query side its card specifies: an inference-free
 * model weighs the query's wordpieces from a frozen IDF table, a symmetric SPLADE model runs the
 * query through the same masked-language-model pass as a document.
 */
internal data class SparseCandidate(
    val name: String,
    val dir: String,
    val queryMode: QueryMode,
    /** The OpenSearch checkpoints zero their special-token dimensions after pooling; SPLADE++ does not. */
    val zeroSpecials: Boolean,
)

internal enum class QueryMode { STATIC_IDF, MLM }
