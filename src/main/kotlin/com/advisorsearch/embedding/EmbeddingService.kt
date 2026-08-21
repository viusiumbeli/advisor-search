package com.advisorsearch.embedding

import com.advisorsearch.config.EmbeddingProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The one embedding path used by both indexing and querying. Sharing it is what makes the two
 * comparable: a query embedded by different code than the corpus is a silent relevance bug.
 */
@Service
class EmbeddingService(
    private val properties: EmbeddingProperties,
    private val tokenizer: WordPieceTokenizer,
    private val embedder: OnnxEmbedder,
    private val chunker: Chunker,
) {
    val modelId: String get() = properties.modelId

    fun chunk(content: String): List<String> = chunker.chunk(content)

    fun embedQuery(query: String): FloatArray = embedder.embed(query)

    /**
     * Embeds a document's chunks in one batched call.
     *
     * Each chunk is embedded with its document title prepended. A chunk pulled from the middle of a
     * long document often has no idea what it is about ("the amount due is payable within 14 days");
     * the title carries that context into the vector at no storage cost, because only the raw chunk
     * text is persisted.
     */
    fun embedChunks(
        title: String,
        chunks: List<String>,
    ): List<FloatArray> {
        if (chunks.isEmpty()) return emptyList()
        val prefix = titlePrefix(title)
        return embedder.embedAll(chunks.map { prefix + it })
    }

    private fun titlePrefix(title: String): String {
        val condensed = title.trim().replace(WHITESPACE, " ")
        if (condensed.isEmpty()) return ""
        return "Title: " + truncateToTokens(condensed, TITLE_TOKEN_BUDGET) + "\n\n"
    }

    /** Drops trailing words until the text fits [budget] wordpieces, measured rather than estimated. */
    private fun truncateToTokens(
        text: String,
        budget: Int,
    ): String {
        if (tokenizer.count(text) <= budget) return text
        val words = text.split(' ')
        for (kept in words.size - 1 downTo 1) {
            val candidate = words.take(kept).joinToString(" ")
            if (tokenizer.count(candidate) <= budget) return candidate
        }
        return words.first()
    }

    init {
        log.info(
            "Embedding model {} ready: {} dimensions, {}-token window, {}-token chunks with {} overlap",
            properties.modelId,
            embedder.dimensions,
            properties.maxTokens,
            properties.chunkTokens,
            properties.chunkOverlapTokens,
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(EmbeddingService::class.java)
        val WHITESPACE = Regex("\\s+")

        /** Leaves the encoder window comfortably clear for a full-size chunk plus [CLS]/[SEP]. */
        const val TITLE_TOKEN_BUDGET = 20
    }
}
