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

/**
 * How a candidate model turns token vectors into one sentence vector. Each checkpoint documents
 * which it was trained with, and using the wrong one silently degrades every similarity.
 */
internal enum class Pooling { MEAN, CLS }
