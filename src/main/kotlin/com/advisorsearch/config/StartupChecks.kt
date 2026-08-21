package com.advisorsearch.config

import com.advisorsearch.documents.DocumentRepository
import com.advisorsearch.embedding.EmbeddingService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Two things are verified before the instance is allowed to report itself ready.
 */
@Component
@Order(0)
class StartupChecks(
    private val documents: DocumentRepository,
    private val embeddings: EmbeddingService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        assertCorpusMatchesModel()
        warmUp()
    }

    /**
     * Vectors from two different models share a column but not a space, and comparing them produces
     * confident nonsense rather than an error. Recording the model per chunk is only useful if
     * something checks it, so a mismatch stops the instance instead of silently degrading search.
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
     * First inference allocates the ONNX arenas and pages in the native libraries. Doing it here
     * means the first user request is not the one that pays for it, and a model that cannot run
     * fails startup rather than the first search.
     */
    private fun warmUp() {
        val startedAt = System.nanoTime()
        val vector = embeddings.embedQuery("warm up the embedding model")
        check(vector.isNotEmpty()) { "Embedding model returned an empty vector" }
        log.info("Embedding warmup completed in {} ms", (System.nanoTime() - startedAt) / 1_000_000)
    }

    private companion object {
        val log = LoggerFactory.getLogger(StartupChecks::class.java)
    }
}
