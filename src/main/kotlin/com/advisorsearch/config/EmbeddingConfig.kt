package com.advisorsearch.config

import com.advisorsearch.embedding.Chunker
import com.advisorsearch.embedding.OnnxEmbedder
import com.advisorsearch.embedding.WordPieceTokenizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import kotlin.io.path.isRegularFile

@Configuration
class EmbeddingConfig {
    @Bean
    fun tokenizer(properties: EmbeddingProperties): WordPieceTokenizer =
        WordPieceTokenizer(existing(properties.tokenizerPath), properties.maxTokens)

    @Bean
    fun embedder(
        properties: EmbeddingProperties,
        tokenizer: WordPieceTokenizer,
    ): OnnxEmbedder = OnnxEmbedder(existing(properties.modelPath), tokenizer, properties.modelId)

    @Bean
    fun chunker(
        properties: EmbeddingProperties,
        tokenizer: WordPieceTokenizer,
    ): Chunker = Chunker(tokenizer, properties.chunkTokens, properties.chunkOverlapTokens)

    /**
     * Fails at startup rather than on the first search. A missing model file is a deployment
     * mistake, and the readiness probe should never report a healthy instance that cannot embed.
     */
    private fun existing(path: String): Path {
        val resolved = Path.of(path).toAbsolutePath()
        check(resolved.isRegularFile()) {
            "Embedding artefact not found at $resolved. Run ./gradlew provisionModel, or set " +
                "EMBEDDING_MODEL_PATH and EMBEDDING_TOKENIZER_PATH to where the files live."
        }
        return resolved
    }
}
