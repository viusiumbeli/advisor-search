package com.advisorsearch.embedding

/** Tokenized text ready for the encoder, padded to a common length across the batch. */
class TokenizedBatch(
    val inputIds: Array<LongArray>,
    val attentionMask: Array<LongArray>,
    val tokenTypeIds: Array<LongArray>,
) {
    val size: Int get() = inputIds.size
    val sequenceLength: Int get() = if (inputIds.isEmpty()) 0 else inputIds[0].size
}
