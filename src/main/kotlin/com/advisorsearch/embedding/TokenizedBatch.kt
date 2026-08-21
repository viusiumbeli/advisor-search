package com.advisorsearch.embedding

/**
 * Tokenized text ready for the encoder, padded to a common length across the batch.
 * Deliberately not a data class: the array fields make generated value semantics wrong, and nothing
 * compares or copies batches.
 */
class TokenizedBatch(
    val inputIds: Array<LongArray>,
    val attentionMask: Array<LongArray>,
    val tokenTypeIds: Array<LongArray>,
) {
    val size: Int get() = inputIds.size
    val sequenceLength: Int get() = if (inputIds.isEmpty()) 0 else inputIds[0].size
}
