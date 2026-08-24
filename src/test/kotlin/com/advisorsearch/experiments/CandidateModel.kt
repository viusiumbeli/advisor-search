package com.advisorsearch.experiments

/** A model under comparison, with the pooling and prefixes its own card specifies — scoring an
 * asymmetric model without its query prefix would measure it unfairly. */
internal data class CandidateModel(
    val name: String,
    val dir: String,
    val pooling: Pooling,
    val queryPrefix: String = "",
    val passagePrefix: String = "",
)

/** Each checkpoint documents which pooling it was trained with; the wrong one silently degrades
 * every similarity. */
internal enum class Pooling { MEAN, CLS }
