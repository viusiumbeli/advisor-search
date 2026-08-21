package com.advisorsearch.documents

import com.advisorsearch.support.toVectorLiteral
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class DocumentRepository(
    private val jdbc: JdbcClient,
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * Writes the document and all of its chunks in one transaction, so a document is never
     * searchable in the keyword index while its vectors are missing. Embedding happens before this
     * call: holding a transaction open across model inference would pin a connection for the whole
     * inference time for no benefit.
     */
    @Transactional
    fun insertWithChunks(
        clientId: UUID,
        title: String,
        content: String,
        chunks: List<String>,
        embeddings: List<FloatArray>,
        modelId: String,
    ): Document {
        require(chunks.size == embeddings.size) { "Each chunk needs exactly one embedding" }
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
                .query(::mapDocument)
                .single()

        jdbcTemplate.batchUpdate(
            """
            INSERT INTO document_chunks (document_id, chunk_index, content, embedding, embedding_model)
            VALUES (?, ?, ?, CAST(? AS vector), ?)
            """.trimIndent(),
            object : BatchPreparedStatementSetter {
                override fun getBatchSize(): Int = chunks.size

                override fun setValues(
                    statement: PreparedStatement,
                    index: Int,
                ) {
                    statement.setObject(1, document.id)
                    statement.setInt(2, index)
                    statement.setString(3, chunks[index])
                    statement.setString(4, embeddings[index].toVectorLiteral())
                    statement.setString(5, modelId)
                }
            },
        )
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
            .query(::mapDocument)
            .optional()
            .orElse(null)

    /**
     * Picks the passages closest to the document's own centroid.
     *
     * `avg(embedding)` is pgvector's mean over the chunk vectors, so the centroid never leaves the
     * database and no vectors have to be parsed back into the JVM.
     */
    fun centralChunks(
        documentId: UUID,
        count: Int,
    ): List<DocumentSummary.Passage> =
        jdbc
            .sql(
                """
                WITH centroid AS (
                    SELECT avg(embedding) AS vector FROM document_chunks WHERE document_id = :id
                )
                SELECT chunk_index, content,
                       1 - (embedding <=> (SELECT vector FROM centroid)) AS centrality
                FROM document_chunks
                WHERE document_id = :id
                ORDER BY embedding <=> (SELECT vector FROM centroid), chunk_index
                LIMIT :count
                """.trimIndent(),
            ).param("id", documentId)
            .param("count", count)
            .query { row, _ ->
                DocumentSummary.Passage(
                    chunkIndex = row.getInt("chunk_index"),
                    text = row.getString("content"),
                    centrality = row.getDouble("centrality"),
                )
            }.list()

    fun existsForClientWithTitle(
        clientId: UUID,
        title: String,
    ): Boolean =
        jdbc
            .sql("SELECT 1 FROM documents WHERE client_id = :clientId AND title = :title")
            .param("clientId", clientId)
            .param("title", title)
            .query(Int::class.java)
            .optional()
            .isPresent

    fun countChunks(documentId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM document_chunks WHERE document_id = :id")
            .param("id", documentId)
            .query(Int::class.java)
            .single()

    /** Model ids present in the corpus, used by the startup consistency check. */
    fun distinctEmbeddingModels(): List<String> =
        jdbc
            .sql("SELECT DISTINCT embedding_model FROM document_chunks")
            .query(String::class.java)
            .list()
            .filterNotNull()

    companion object {
        fun mapDocument(
            row: ResultSet,
            @Suppress("UNUSED_PARAMETER") rowNumber: Int,
        ): Document =
            Document(
                id = row.getObject("id", UUID::class.java),
                clientId = row.getObject("client_id", UUID::class.java),
                title = row.getString("title"),
                content = row.getString("content"),
                createdAt = row.getObject("created_at", OffsetDateTime::class.java),
            )
    }
}
