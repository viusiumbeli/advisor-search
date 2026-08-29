package com.advisorsearch.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.slf4j.LoggerFactory
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Path

private val log = LoggerFactory.getLogger(SparseEncoder::class.java)

/**
 * How many texts share one ONNX call. The masked-language-model head emits `[batch, sequence, 30522]`
 * float32 logits — about 27 MiB for a 230-token chunk — and ONNX Runtime's Java binding copies the
 * tensor onto the heap to read it, so it exists twice. Sixteen at a time, as `EMBED_BATCH` does for
 * the dense model, would peak near 860 MiB against a 2 GB machine; four keeps it around 220 MiB for
 * a few milliseconds of extra call overhead.
 */
private const val SPARSE_BATCH = 4

/**
 * A SPLADE-family masked-language-model encoder, in-process on ONNX Runtime: the logits over the
 * whole vocabulary at every position, max-pooled over the real tokens, then `log(1 + relu(·))` —
 * the published pipeline for every checkpoint in the family. Weights stay unnormalised because the
 * models are trained against a plain inner product, so `-(a <#> b)` in Postgres is the score.
 */
class SparseEncoder(
    modelPath: Path,
    private val tokenizer: WordPieceTokenizer,
    val modelId: String,
    /** Vocabulary ids zeroed after pooling: the OpenSearch checkpoints zero their five specials, SPLADE++ zeroes nothing. */
    private val specialTokenIds: IntArray,
    /** Terms kept per text, heaviest first. Why it exists: `sparse.max-terms` in application.yml. */
    val maxTerms: Int,
) : AutoCloseable {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = environment.createSession(modelPath.toString(), OrtSession.SessionOptions())

    init {
        require(maxTerms > 0) { "maxTerms must be positive" }
        log.info(
            "Loaded sparse model {} from {} (inputs={}, outputs={})",
            modelId,
            modelPath,
            session.inputNames,
            session.outputNames,
        )
    }

    fun encode(text: String): SparseVector = encodeAll(listOf(text)).single()

    fun encodeAll(texts: List<String>): List<SparseVector> = texts.chunked(SPARSE_BATCH).flatMap(::encodeBatch)

    private fun encodeBatch(texts: List<String>): List<SparseVector> {
        val batch = tokenizer.encode(texts)
        val shape = longArrayOf(batch.size.toLong(), batch.sequenceLength.toLong())

        val tensors = LinkedHashMap<String, OnnxTensor>()
        try {
            // Only feed inputs this export actually declares: DistilBERT exports omit token_type_ids.
            fun offer(
                name: String,
                rows: Array<LongArray>,
            ) {
                if (name in session.inputNames) {
                    tensors[name] = OnnxTensor.createTensor(environment, LongBuffer.wrap(flatten(rows)), shape)
                }
            }
            offer("input_ids", batch.inputIds)
            offer("attention_mask", batch.attentionMask)
            offer("token_type_ids", batch.tokenTypeIds)

            session.run(tensors).use { result ->
                val logits = result[0] as OnnxTensor
                val (rows, length, width) = logits.info.shape.map(Long::toInt)
                check(width == SPARSE_DIMENSIONS) { "Model produced $width vocabulary terms, expected $SPARSE_DIMENSIONS" }
                // One heap copy of the whole tensor per batch, never one per row.
                val values = logits.floatBuffer
                return (0 until rows).map { row ->
                    poolMaxLogits(values, row, length, width, batch.attentionMask[row], specialTokenIds, maxTerms)
                }
            }
        } finally {
            tensors.values.forEach(OnnxTensor::close)
        }
    }

    override fun close() {
        session.close()
    }
}

/**
 * The published pooling, rearranged for the loop. The reference takes `log(1 + relu(logit))` at every
 * position and then the maximum over positions; both functions are monotone, so taking the maximum
 * of the raw logits first and transforming once per term gives the same vector for 30,522
 * transcendental calls instead of sequence × 30,522. Starting the accumulator at zero is the mask
 * and the relu at once: padded positions are skipped, and a term whose logit never rises above zero
 * stays at zero — which is where the sparsity comes from.
 */
internal fun poolMaxLogits(
    values: FloatBuffer,
    row: Int,
    sequenceLength: Int,
    width: Int,
    attentionMask: LongArray,
    specialTokenIds: IntArray,
    maxTerms: Int,
): SparseVector {
    val pooled = FloatArray(width)
    for (position in 0 until sequenceLength) {
        if (attentionMask[position] == 0L) continue
        val offset = (row * sequenceLength + position) * width
        for (term in 0 until width) {
            val logit = values.get(offset + term)
            if (logit > pooled[term]) pooled[term] = logit
        }
    }
    for (id in specialTokenIds) pooled[id] = 0f
    return sparseVectorOf(pooled, maxTerms)
}
