package com.advisorsearch.embedding

import java.nio.file.Path
import kotlin.math.sqrt

/**
 * Loads the real model once for the whole test JVM. Tests use the shipped artefacts rather than a
 * stub so that anything they assert about tokenisation or similarity is true of production too.
 */
object TestModel {
    val tokenizerPath: Path = Path.of("models/tokenizer.json")
    val modelPath: Path = Path.of("models/model.onnx")

    val tokenizer: WordPieceTokenizer by lazy { WordPieceTokenizer(tokenizerPath, maxTokens = 256) }
    val embedder: OnnxEmbedder by lazy { OnnxEmbedder(modelPath, tokenizer, modelId = "all-MiniLM-L6-v2") }
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
