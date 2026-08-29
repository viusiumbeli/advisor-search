package com.advisorsearch.embedding

import com.advisorsearch.config.EmbeddingProperties
import com.advisorsearch.support.WHITESPACE_RUN
import com.advisorsearch.support.wholeCharacterEnd
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(EmbeddingService::class.java)

/** Leaves the encoder window comfortably clear for a full-size chunk plus [CLS]/[SEP]. */
private const val TITLE_TOKEN_BUDGET = 20

/**
 * How many chunks are padded into one ONNX call. Self-attention allocates on the order of
 * batch × heads × sequence² of native memory, outside the heap and outside `MaxRAMPercentage`, so
 * an uncapped batch makes peak memory a function of document length times concurrent ingests. A
 * document at the 100,000-character cap is ~150 chunks; batching them in sixteens costs a few
 * milliseconds of extra call overhead and keeps the peak flat.
 */
private const val EMBED_BATCH = 16

/**
 * The one encoding path used by both indexing and querying, for both models. Sharing it is what
 * makes a query and the corpus comparable: a query encoded by different code than the corpus — or
 * a chunk whose title prefix one model saw and the other did not — is a silent relevance bug.
 */
@Service
class EmbeddingService(
    private val properties: EmbeddingProperties,
    private val tokenizer: WordPieceTokenizer,
    private val embedder: OnnxEmbedder,
    private val sparse: SparseEncoder,
    private val idf: IdfTable,
    private val chunker: Chunker,
) {
    val modelId: String get() = properties.modelId

    val sparseModelId: String get() = sparse.modelId

    fun chunk(content: String): List<String> = chunker.chunk(content)

    fun embedQuery(query: String): FloatArray = embedder.embed(query)

    /**
     * Embeds several query probes as one padded batch: five expansion probes cost one transformer
     * forward pass instead of five. Padding is masked out of the mean pooling, so the vectors are
     * the same ones the texts would get individually.
     */
    fun embedQueries(queries: List<String>): List<FloatArray> = embedder.embedAll(queries)

    /**
     * Embeds a document's chunks in padded calls of [EMBED_BATCH], each chunk with the document title
     * prepended. A chunk from the middle of a long document often has no idea what it is about ("the
     * amount due is payable within 14 days"); the title carries that into the vector at no storage
     * cost, because only the raw chunk text is persisted.
     */
    fun embedChunks(
        title: String,
        chunks: List<String>,
    ): List<FloatArray> {
        if (chunks.isEmpty()) return emptyList()
        val prefix = titlePrefix(title)
        return chunks.chunked(EMBED_BATCH).flatMap { batch -> embedder.embedAll(batch.map { prefix + it }) }
    }

    /**
     * The inference-free query side: each probe becomes its distinct wordpieces weighted from the
     * checkpoint's IDF table — a lookup, not a forward pass, which is why the sparse arm adds nothing
     * measurable to a search's model time.
     */
    fun encodeQueriesSparsely(queries: List<String>): List<SparseVector> = queries.map { idf.weigh(tokenizer.tokenIds(it)) }

    /**
     * The sparse twin of [embedChunks], with the same title prefix so both models describe the same
     * text. Here the title's terms enter every chunk's expansion, which is the role `setweight('A')`
     * plays for the lexical arm.
     */
    fun encodeChunksSparsely(
        title: String,
        chunks: List<String>,
    ): List<SparseVector> {
        if (chunks.isEmpty()) return emptyList()
        val prefix = titlePrefix(title)
        return sparse.encodeAll(chunks.map { prefix + it })
    }

    private fun titlePrefix(title: String): String {
        val condensed = title.trim().replace(WHITESPACE_RUN, " ")
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
        // One word can exceed the budget on its own: WordPiece splits on punctuation too, so a
        // title that is a long filename or URL has no space to cut at and tokenizes into dozens of
        // pieces. Returning it whole would push the prefix past the encoder window and truncate the
        // chunk it was meant to describe, so the last resort halves characters until it measures.
        var candidate = words.first()
        while (tokenizer.count(candidate) > budget && candidate.length > 1) {
            candidate = candidate.substring(0, candidate.wholeCharacterEnd(candidate.length / 2))
        }
        return candidate
    }

    init {
        log.info(
            "Embedding model {} ready: {} dimensions, {}-token window, {}-token chunks with {} overlap",
            properties.modelId,
            EMBEDDING_DIMENSIONS,
            properties.maxTokens,
            properties.chunkTokens,
            properties.chunkOverlapTokens,
        )
        log.info(
            "Sparse model {} ready: {} vocabulary terms, IDF-weighted query wordpieces, at most {} terms stored per chunk",
            sparse.modelId,
            idf.size,
            sparse.maxTerms,
        )
    }
}
