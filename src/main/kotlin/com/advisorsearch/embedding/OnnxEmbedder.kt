package com.advisorsearch.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.slf4j.LoggerFactory
import java.nio.LongBuffer
import java.nio.file.Path
import kotlin.math.sqrt

private val log = LoggerFactory.getLogger(OnnxEmbedder::class.java)

/** all-MiniLM-L6-v2 hidden size; also the `vector(384)` column width in the schema. */
const val EMBEDDING_DIMENSIONS = 384

/**
 * all-MiniLM-L6-v2 in-process on ONNX Runtime, reproducing the checkpoint's published pipeline:
 * transformer, mean pooling, L2 normalisation. Normalising is what makes the stored vectors unit
 * length, so `1 - (a <=> b)` in Postgres is directly the cosine similarity.
 */
class OnnxEmbedder(
    modelPath: Path,
    private val tokenizer: WordPieceTokenizer,
    val modelId: String,
) : AutoCloseable {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = environment.createSession(modelPath.toString(), OrtSession.SessionOptions())

    init {
        log.info(
            "Loaded embedding model {} from {} (inputs={}, outputs={})",
            modelId,
            modelPath,
            session.inputNames,
            session.outputNames,
        )
    }

    fun embed(text: String): FloatArray = embedAll(listOf(text)).single()

    fun embedAll(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val batch = tokenizer.encode(texts)
        val shape = longArrayOf(batch.size.toLong(), batch.sequenceLength.toLong())

        val tensors = LinkedHashMap<String, OnnxTensor>()
        try {
            // Only feed inputs this export actually declares: some exports omit token_type_ids.
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
                val hidden = result[0] as OnnxTensor
                return meanPool(hidden, batch.attentionMask)
            }
        } finally {
            tensors.values.forEach(OnnxTensor::close)
        }
    }

    /**
     * Averages token vectors over the real (unmasked) tokens only, then scales to unit length.
     * Padding positions must be excluded or short texts in a wide batch drift toward the pad vector.
     */
    private fun meanPool(
        hidden: OnnxTensor,
        attentionMask: Array<LongArray>,
    ): List<FloatArray> {
        val (batchSize, sequenceLength, width) = hidden.info.shape.map(Long::toInt)
        check(width == EMBEDDING_DIMENSIONS) { "Model produced $width dimensions, expected $EMBEDDING_DIMENSIONS" }
        val values = hidden.floatBuffer
        return (0 until batchSize).map { row ->
            val pooled = FloatArray(width)
            var live = 0
            for (position in 0 until sequenceLength) {
                if (attentionMask[row][position] == 0L) continue
                live++
                val offset = (row * sequenceLength + position) * width
                for (dimension in 0 until width) {
                    pooled[dimension] += values.get(offset + dimension)
                }
            }
            val divisor = maxOf(live, 1).toFloat()
            for (dimension in 0 until width) {
                pooled[dimension] /= divisor
            }
            normalize(pooled)
        }
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var sumOfSquares = 0.0
        for (value in vector) sumOfSquares += (value * value).toDouble()
        val length = sqrt(sumOfSquares).toFloat()
        if (length == 0f) return vector
        for (index in vector.indices) vector[index] /= length
        return vector
    }

    override fun close() {
        session.close()
    }
}

private fun flatten(rows: Array<LongArray>): LongArray {
    val width = rows[0].size
    val flat = LongArray(rows.size * width)
    rows.forEachIndexed { index, row -> row.copyInto(flat, index * width) }
    return flat
}
