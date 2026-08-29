package com.advisorsearch.config

import com.advisorsearch.embedding.Chunker
import com.advisorsearch.embedding.IdfTable
import com.advisorsearch.embedding.OnnxEmbedder
import com.advisorsearch.embedding.SparseEncoder
import com.advisorsearch.embedding.WordPieceTokenizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
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
    fun idfTable(
        sparse: SparseProperties,
        embedding: EmbeddingProperties,
        objectMapper: ObjectMapper,
    ): IdfTable = IdfTable.load(existing(sparse.idfPath), existing(sparse.tokenizerPath), existing(embedding.tokenizerPath), objectMapper)

    /** Shares the dense model's tokenizer: IdfTable.load has just proved the two vocabularies identical. */
    @Bean
    fun sparseEncoder(
        sparse: SparseProperties,
        tokenizer: WordPieceTokenizer,
        idf: IdfTable,
    ): SparseEncoder = SparseEncoder(existing(sparse.modelPath), tokenizer, sparse.modelId, idf.specialTokenIds, sparse.maxTerms)

    @Bean
    fun chunker(
        properties: EmbeddingProperties,
        tokenizer: WordPieceTokenizer,
    ): Chunker = Chunker(tokenizer, properties.chunkTokens, properties.chunkOverlapTokens)

    /** Fails at startup, not on the first search: readiness must never pass on an instance that cannot encode. */
    private fun existing(path: String): Path {
        val resolved = Path.of(path).toAbsolutePath()
        check(resolved.isRegularFile()) {
            "Model artefact not found at $resolved. Run ./gradlew provisionModel, or set " +
                "EMBEDDING_MODEL_PATH, EMBEDDING_TOKENIZER_PATH, SPARSE_MODEL_PATH, SPARSE_TOKENIZER_PATH " +
                "and SPARSE_IDF_PATH to where the files live."
        }
        return resolved
    }
}
