package com.advisorsearch.embedding

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import java.nio.file.Path

/**
 * Tokenized text ready for the encoder, padded to a common length across the batch.
 * Deliberately not a data class: the array fields make generated value semantics wrong, and
 * nothing compares or copies batches.
 */
class TokenizedBatch(
    val inputIds: Array<LongArray>,
    val attentionMask: Array<LongArray>,
    val tokenTypeIds: Array<LongArray>,
) {
    val size: Int get() = inputIds.size
    val sequenceLength: Int get() = if (inputIds.isEmpty()) 0 else inputIds[0].size
}

/**
 * The single tokenizer used for both indexing and querying.
 *
 * Two underlying instances are kept on purpose. The encoding one truncates at [maxTokens] because
 * that is what the model can consume; the counting one never truncates, because a counter that
 * saturates at the limit could not tell the chunker that a piece of text is too long.
 */
class WordPieceTokenizer(
    tokenizerPath: Path,
    val maxTokens: Int,
) : AutoCloseable {
    private val encoder: HuggingFaceTokenizer =
        HuggingFaceTokenizer
            .builder()
            .optTokenizerPath(tokenizerPath)
            .optAddSpecialTokens(true)
            .optTruncation(true)
            .optMaxLength(maxTokens)
            .build()

    private val counter: HuggingFaceTokenizer =
        HuggingFaceTokenizer
            .builder()
            .optTokenizerPath(tokenizerPath)
            .optAddSpecialTokens(false)
            .optTruncation(false)
            .build()

    /** Wordpieces in [text], excluding the [CLS]/[SEP] the encoder adds later. */
    fun count(text: String): Int = counter.encode(text).ids.size

    fun encode(texts: List<String>): TokenizedBatch {
        require(texts.isNotEmpty()) { "Cannot encode an empty batch" }
        val encodings = encoder.batchEncode(texts)
        val width = encodings.maxOf { it.ids.size }

        // Padding is applied here rather than delegated to the tokenizer so that the batch shape is
        // decided by this code and visible in tests, instead of depending on tokenizer defaults.
        fun pad(rows: List<LongArray>): Array<LongArray> =
            Array(rows.size) { row ->
                LongArray(width) { column -> rows[row].getOrElse(column) { 0L } }
            }
        return TokenizedBatch(
            inputIds = pad(encodings.map { it.ids }),
            attentionMask = pad(encodings.map { it.attentionMask }),
            tokenTypeIds = pad(encodings.map { it.typeIds }),
        )
    }

    /** True when [text] does not fit in [maxTokens] and the encoder would drop the tail. */
    fun exceedsWindow(text: String): Boolean = count(text) + SPECIAL_TOKENS > maxTokens

    override fun close() {
        encoder.close()
        counter.close()
    }
}

/** [CLS] and [SEP]. */
private const val SPECIAL_TOKENS = 2
