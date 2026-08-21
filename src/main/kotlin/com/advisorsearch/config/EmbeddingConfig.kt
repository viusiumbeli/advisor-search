package com.advisorsearch.config

import com.advisorsearch.embedding.Chunker
import com.advisorsearch.embedding.OnnxEmbedder
import com.advisorsearch.embedding.WordPieceTokenizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path

@Configuration
class EmbeddingConfig {
    @Bean(destroyMethod = "close")
    fun tokenizer(properties: EmbeddingProperties): WordPieceTokenizer =
        WordPieceTokenizer(existing(properties.tokenizerPath), properties.maxTokens)

    @Bean(destroyMethod = "close")
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
        check(Files.isRegularFile(resolved)) {
            "Embedding artefact not found at $resolved. Run ./gradlew provisionModel, or set " +
                "EMBEDDING_MODEL_PATH and EMBEDDING_TOKENIZER_PATH to where the files live."
        }
        return resolved
    }
}
