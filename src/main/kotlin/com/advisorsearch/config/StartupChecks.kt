package com.advisorsearch.config

import com.advisorsearch.documents.DocumentRepository
import com.advisorsearch.embedding.EmbeddingService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import kotlin.time.measureTimedValue

private val log = LoggerFactory.getLogger(StartupChecks::class.java)

/** Both models are verified against the corpus and warmed before the instance reports itself ready. */
@Component
@Order(0)
class StartupChecks(
    private val documents: DocumentRepository,
    private val embeddings: EmbeddingService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        assertCorpusMatchesModel()
        assertCorpusMatchesSparseModel()
        warmUp()
    }

    /**
     * Vectors from two models share a column but not a space, and comparing them yields confident
     * nonsense rather than an error — so a mismatch stops the instance instead of degrading search.
     */
    private fun assertCorpusMatchesModel() {
        val foreign = documents.distinctEmbeddingModels().filter { it != embeddings.modelId }
        check(foreign.isEmpty()) {
            "Corpus contains embeddings from ${foreign.joinToString()} but this instance is " +
                "configured for ${embeddings.modelId}. Reindex the documents or start with the " +
                "matching model; mixing embedding spaces silently corrupts ranking."
        }
    }

    /**
     * The same argument, sharper: term weights from two sparse checkpoints share a vocabulary axis,
     * so a mixed corpus would not even look wrong — it would rank plausibly on a scale nobody set.
     */
    private fun assertCorpusMatchesSparseModel() {
        val foreign = documents.distinctSparseModels().filter { it != embeddings.sparseModelId }
        check(foreign.isEmpty()) {
            "Corpus contains sparse vectors from ${foreign.joinToString()} but this instance is " +
                "configured for ${embeddings.sparseModelId}. Reindex the documents or start with the " +
                "matching model; mixing term-weight scales silently corrupts ranking."
        }
    }

    /**
     * First inference allocates the ONNX arenas and pages in the native libraries. Paying that here
     * means no user request does, and a model that cannot run fails startup rather than a search.
     * The sparse model is warmed with a document pass: its query side is a table lookup, and only a
     * document pass allocates the vocabulary-wide arena the MLM head needs.
     */
    private fun warmUp() {
        val (vector, elapsed) = measureTimedValue { embeddings.embedQuery("warm up the embedding model") }
        check(vector.isNotEmpty()) { "Embedding model returned an empty vector" }
        val (sparse, sparseElapsed) = measureTimedValue { embeddings.encodeChunksSparsely("Warm up", listOf("warm up the sparse model")) }
        check(sparse.single().termCount > 0) { "Sparse model returned an empty vector" }
        log.info("Embedding warmup completed in {}, sparse warmup in {}", elapsed, sparseElapsed)
    }
}
