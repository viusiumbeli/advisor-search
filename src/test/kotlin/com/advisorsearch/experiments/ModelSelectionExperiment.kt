package com.advisorsearch.experiments

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.advisorsearch.embedding.Chunker
import com.advisorsearch.embedding.WordPieceTokenizer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import java.nio.LongBuffer
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.math.sqrt

/**
 * The experiment behind the decision to add a query expansion lexicon rather than reach for a bigger
 * embedding model. It ranks every seeded document against a set of probe queries, once per candidate
 * model, using each model's own documented pooling and query prefix.
 *
 * It is not part of the suite's guarantees and passes as a no-op unless the candidate models are
 * downloaded, because they are 100 MB each and only needed to reproduce the measurement:
 *
 *   ./gradlew test --tests '*ModelSelectionExperiment' -i \
 *       -Dcandidates=/path/containing/multiqa,msmarco,e5small,bgesmall
 *
 * Each subdirectory holds that model's model.onnx and tokenizer.json. The result is reported in the
 * README under "Why the task's own example needs more than a model".
 */
class ModelSelectionExperiment {
    private enum class Pooling { MEAN, CLS }

    private data class Candidate(
        val name: String,
        val dir: String,
        val pooling: Pooling,
        val queryPrefix: String = "",
        val passagePrefix: String = "",
    )

    private val candidates =
        listOf(
            Candidate("all-MiniLM-L6-v2 (current)", "baseline", Pooling.MEAN),
            Candidate("multi-qa-MiniLM-L6-cos-v1", "multiqa", Pooling.MEAN),
            Candidate("msmarco-MiniLM-L6-cos-v5", "msmarco", Pooling.MEAN),
            Candidate("e5-small-v2", "e5small", Pooling.MEAN, "query: ", "passage: "),
            Candidate("bge-small-en-v1.5", "bgesmall", Pooling.CLS, "Represent this sentence for searching relevant passages: "),
        )

    private val probes =
        listOf(
            "address proof" to "electricity-account-statement",
            "proof of address" to "electricity-account-statement",
            "utility bill" to "electricity-account-statement",
            "who can act for a client if they lose capacity" to "lasting-power-of-attorney",
            "retirement income planning" to "suitability-report",
            "inheritance tax on gifts" to "meeting-notes-estate-planning",
            "share options vesting" to "share-option-scheme",
            "rental yield" to "buy-to-let-portfolio-review",
        )

    @Test
    @EnabledIfSystemProperty(named = "candidates", matches = ".+")
    fun `compare candidate models on the probe queries`() {
        val root = System.getProperty("candidates")
        val documents =
            Path
                .of("src/main/resources/seed/documents")
                .listDirectoryEntries("*.txt")
                .map(java.nio.file.Path::toFile)
                .sortedBy { it.name }

        candidates.forEach { candidate ->
            val dir = File(root, if (candidate.dir == "baseline") "" else candidate.dir)
            val modelPath = if (candidate.dir == "baseline") Path.of("models/model.onnx") else File(dir, "model.onnx").toPath()
            val tokenizerPath =
                if (candidate.dir == "baseline") Path.of("models/tokenizer.json") else File(dir, "tokenizer.json").toPath()
            if (!modelPath.toFile().isFile) return@forEach println("skipped ${candidate.name}: no model at $modelPath")

            WordPieceTokenizer(tokenizerPath, maxTokens = 512).use { tokenizer ->
                val chunker = Chunker(tokenizer, budgetTokens = 200, overlapTokens = 30)
                val environment = OrtEnvironment.getEnvironment()
                val session = environment.createSession(modelPath.toString(), OrtSession.SessionOptions())

                val chunksByDocument =
                    documents.associate { file ->
                        file.name to chunker.chunk(file.readText()).map { candidate.passagePrefix + it }
                    }
                val vectorsByDocument =
                    chunksByDocument.mapValues { (_, chunks) ->
                        chunks.chunked(16).flatMap { batch -> encode(session, environment, tokenizer, batch, candidate.pooling) }
                    }

                println("\n=== ${candidate.name} ===")
                var hits = 0
                var reciprocalRankSum = 0.0
                probes.forEach { (query, expected) ->
                    val queryVector =
                        encode(
                            session,
                            environment,
                            tokenizer,
                            listOf(candidate.queryPrefix + query),
                            candidate.pooling,
                        ).single()
                    val ranked =
                        vectorsByDocument
                            .map { (name, vectors) -> name to (vectors.maxOfOrNull { cosine(queryVector, it) } ?: 0.0) }
                            .sortedByDescending { it.second }
                    val rank = ranked.indexOfFirst { it.first.startsWith(expected) } + 1
                    val score = ranked.first { it.first.startsWith(expected) }.second
                    if (rank in 1..5) hits++
                    reciprocalRankSum += 1.0 / rank
                    println(
                        "%-48s rank %2d  cosine %.4f   top: %s (%.4f)".format(
                            "\"$query\"",
                            rank,
                            score,
                            ranked
                                .first()
                                .first
                                .removeSuffix(".txt")
                                .take(38),
                            ranked.first().second,
                        ),
                    )
                }
                println("hit@5 = $hits/${probes.size}   MRR = %.3f".format(reciprocalRankSum / probes.size))
                session.close()
            }
        }
    }

    private fun encode(
        session: OrtSession,
        environment: OrtEnvironment,
        tokenizer: WordPieceTokenizer,
        texts: List<String>,
        pooling: Pooling,
    ): List<FloatArray> {
        val batch = tokenizer.encode(texts)
        val shape = longArrayOf(batch.size.toLong(), batch.sequenceLength.toLong())
        val tensors = LinkedHashMap<String, OnnxTensor>()
        try {
            fun offer(
                name: String,
                rows: Array<LongArray>,
            ) {
                if (name in session.inputNames) {
                    val flat = LongArray(rows.size * rows[0].size)
                    rows.forEachIndexed { index, row -> row.copyInto(flat, index * rows[0].size) }
                    tensors[name] = OnnxTensor.createTensor(environment, LongBuffer.wrap(flat), shape)
                }
            }
            offer("input_ids", batch.inputIds)
            offer("attention_mask", batch.attentionMask)
            offer("token_type_ids", batch.tokenTypeIds)
            session.run(tensors).use { result ->
                val hidden = result[0] as OnnxTensor
                val (rows, length, width) = hidden.info.shape.map(Long::toInt)
                val values = hidden.floatBuffer
                return (0 until rows).map { row ->
                    val pooled = FloatArray(width)
                    if (pooling == Pooling.CLS) {
                        for (dimension in 0 until width) pooled[dimension] = values.get(row * length * width + dimension)
                    } else {
                        var live = 0
                        for (position in 0 until length) {
                            if (batch.attentionMask[row][position] == 0L) continue
                            live++
                            val offset = (row * length + position) * width
                            for (dimension in 0 until width) pooled[dimension] += values.get(offset + dimension)
                        }
                        for (dimension in 0 until width) pooled[dimension] /= maxOf(live, 1).toFloat()
                    }
                    normalise(pooled)
                }
            }
        } finally {
            tensors.values.forEach(OnnxTensor::close)
        }
    }

    private fun normalise(vector: FloatArray): FloatArray {
        val length = sqrt(vector.fold(0.0) { acc, value -> acc + value * value }).toFloat()
        if (length > 0) for (index in vector.indices) vector[index] /= length
        return vector
    }

    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        var dot = 0.0
        for (index in a.indices) dot += (a[index] * b[index]).toDouble()
        return dot
    }
}
