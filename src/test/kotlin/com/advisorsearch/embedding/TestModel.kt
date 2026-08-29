package com.advisorsearch.embedding

import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import kotlin.math.sqrt

/**
 * Loads the real models once for the whole test JVM. Tests use the shipped artefacts rather than a
 * stub so that anything they assert about tokenisation or similarity is true of production too.
 */
object TestModel {
    val tokenizerPath: Path = Path.of("models/tokenizer.json")
    val modelPath: Path = Path.of("models/model.onnx")
    val sparseModelPath: Path = Path.of("models/sparse-model.onnx")
    val sparseTokenizerPath: Path = Path.of("models/sparse-tokenizer.json")
    val sparseIdfPath: Path = Path.of("models/sparse-idf.json")

    const val SPARSE_MODEL_ID = "opensearch-neural-sparse-encoding-doc-v2-mini"

    val tokenizer: WordPieceTokenizer by lazy { WordPieceTokenizer(tokenizerPath, maxTokens = 256) }
    val embedder: OnnxEmbedder by lazy { OnnxEmbedder(modelPath, tokenizer, modelId = "all-MiniLM-L6-v2") }
    val idfTable: IdfTable by lazy { IdfTable.load(sparseIdfPath, sparseTokenizerPath, tokenizerPath, JsonMapper()) }
    val sparseEncoder: SparseEncoder by lazy {
        SparseEncoder(sparseModelPath, tokenizer, SPARSE_MODEL_ID, idfTable.specialTokenIds, maxTerms = 1000)
    }

    /** Vocabulary id to wordpiece, for reading a sparse vector's terms in a test. */
    val vocabulary: Array<String> by lazy {
        val words = Array(SPARSE_DIMENSIONS) { "" }
        for ((token, id) in JsonMapper()
            .readTree(sparseTokenizerPath)
            .path("model")
            .path("vocab")
            .properties()) {
            words[id.asInt()] = token
        }
        words
    }

    fun tokenId(word: String): Int = vocabulary.indexOf(word).also { require(it >= 0) { "'$word' is not a wordpiece" } }
}

fun cosine(
    a: FloatArray,
    b: FloatArray,
): Double {
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (index in a.indices) {
        dot += (a[index] * b[index]).toDouble()
        normA += (a[index] * a[index]).toDouble()
        normB += (b[index] * b[index]).toDouble()
    }
    return dot / (sqrt(normA) * sqrt(normB))
}
