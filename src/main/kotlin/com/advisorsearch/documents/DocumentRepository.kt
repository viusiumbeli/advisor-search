package com.advisorsearch.documents

import com.advisorsearch.embedding.SparseVector
import com.advisorsearch.support.toSparseVectorLiteral
import com.advisorsearch.support.toVectorLiteral
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Repository
class DocumentRepository(
    private val jdbc: JdbcClient,
) {
    /**
     * Document and chunks in one transaction, so a document is never in the keyword index while its
     * vectors are missing. Both encodings happen before the call, to keep a connection off inference.
     */
    @Transactional
    fun insertWithChunks(
        clientId: UUID,
        title: String,
        content: String,
        chunks: List<String>,
        embeddings: List<FloatArray>,
        modelId: String,
        sparseVectors: List<SparseVector>,
        sparseModelId: String,
    ): Document {
        require(chunks.size == embeddings.size && chunks.size == sparseVectors.size) {
            "Each chunk needs exactly one dense and one sparse vector"
        }
        val document =
            jdbc
                .sql(
                    """
                    INSERT INTO documents (client_id, title, content)
                    VALUES (:clientId, :title, :content)
                    RETURNING id, client_id, title, content, created_at
                    """.trimIndent(),
                ).param("clientId", clientId)
                .param("title", title)
                .param("content", content)
                .query(Document::class.java)
                .single()

        // One set-based statement instead of a JDBC batch: the chunks and their two vectors travel
        // as three parallel arrays and Postgres unrolls them server-side, so a 22-chunk document is
        // one round trip. WITH ORDINALITY numbers the elements, which is exactly chunk_index + 1.
        jdbc
            .sql(
                """
                INSERT INTO document_chunks
                    (document_id, chunk_index, content, embedding, embedding_model, sparse_embedding, sparse_model)
                SELECT :documentId, i - 1, c, CAST(v AS vector), :modelId, CAST(s AS sparsevec), :sparseModelId
                FROM unnest(CAST(:contents AS text[]), CAST(:vectors AS text[]), CAST(:sparse AS text[]))
                     WITH ORDINALITY AS t(c, v, s, i)
                """.trimIndent(),
            ).param("documentId", document.id)
            .param("contents", chunks.toTypedArray())
            .param("vectors", embeddings.map { it.toVectorLiteral() }.toTypedArray())
            .param("modelId", modelId)
            .param("sparse", sparseVectors.map { it.toSparseVectorLiteral() }.toTypedArray())
            .param("sparseModelId", sparseModelId)
            .update()
        return document
    }

    fun findById(id: UUID): Document? =
        jdbc
            .sql(
                """
                SELECT id, client_id, title, content, created_at
                FROM documents WHERE id = :id
                """.trimIndent(),
            ).param("id", id)
            .query(Document::class.java)
            .optional()
            .getOrNull()

    /**
     * Picks the passages closest to the document's own centroid. `avg(embedding)` is pgvector's mean,
     * so the centroid never leaves the database and no vectors are parsed back into the JVM.
     */
    fun centralChunks(
        documentId: UUID,
        count: Int,
    ): List<SummaryPassage> =
        jdbc
            .sql(
                """
                WITH centroid AS (
                    SELECT avg(embedding) AS vector FROM document_chunks WHERE document_id = :id
                )
                SELECT chunk_index, content AS text,
                       1 - (embedding <=> (SELECT vector FROM centroid)) AS centrality
                FROM document_chunks
                WHERE document_id = :id
                ORDER BY embedding <=> (SELECT vector FROM centroid), chunk_index
                LIMIT :count
                """.trimIndent(),
            ).param("id", documentId)
            .param("count", count)
            .query(SummaryPassage::class.java)
            .list()
            .filterNotNull()

    fun countChunks(documentId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM document_chunks WHERE document_id = :id")
            .param("id", documentId)
            .query(Int::class.java)
            .single()

    fun existsForClientWithTitle(
        clientId: UUID,
        title: String,
    ): Boolean =
        jdbc
            .sql("SELECT EXISTS(SELECT 1 FROM documents WHERE client_id = :clientId AND title = :title)")
            .param("clientId", clientId)
            .param("title", title)
            .query(Boolean::class.java)
            .single()

    /** Dense model ids present in the corpus, used by the startup consistency check. */
    fun distinctEmbeddingModels(): List<String> =
        jdbc
            .sql("SELECT DISTINCT embedding_model FROM document_chunks")
            .query(String::class.java)
            .list()
            .filterNotNull()

    /** Sparse model ids present in the corpus, for the same check. */
    fun distinctSparseModels(): List<String> =
        jdbc
            .sql("SELECT DISTINCT sparse_model FROM document_chunks")
            .query(String::class.java)
            .list()
            .filterNotNull()
}
