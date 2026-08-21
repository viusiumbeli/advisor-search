package com.advisorsearch.experiments

/**
 * One embedding model under comparison, with the pooling and prefixes its own model card specifies —
 * an asymmetric model scored without its query prefix would be measured unfairly.
 */
internal data class CandidateModel(
    val name: String,
    val dir: String,
    val pooling: Pooling,
    val queryPrefix: String = "",
    val passagePrefix: String = "",
)
